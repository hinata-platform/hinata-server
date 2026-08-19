package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueActivity;
import com.ahmadre.hinata.issue.IssueActivityRepository;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueLinkService;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an export refuses to do with content somebody else wrote.
 *
 * <p>Separate from {@code IssueExportRenderersTest}, which asks whether the four
 * formats come out readable. These ask the opposite question: what happens when
 * the description, the comments and the issue's own id fields are not a document
 * but an attempt — a table shaped to cost a thousand times what it took to
 * write, an id pointing into a project the caller was never added to, a user
 * whose only name on file is their mail address.
 */
class ExportHardeningTest {

	private static final Instant AT = Instant.parse("2026-08-20T08:00:00Z");

	// --- the renderers -------------------------------------------------------

	private static IssueExport export(String title, List<ExportBlock> description) {
		return new IssueExport("HIN-50", title, "p", List.of(), description,
				List.of(), List.of(), List.of(), List.of(), "org", AT);
	}

	/**
	 * Markdown for a table [columns] wide whose body rows carry a single cell.
	 *
	 * <p>That shape is the attack: the header row is what declares the width, and
	 * every short row underneath is padded back out to it by the PDF renderer and
	 * by Word's own table model. Writing it costs {@code columns + rows}
	 * characters; laying it out costs {@code columns × rows} cells.
	 */
	private static String wideTable(int columns, int rows) {
		StringBuilder md = new StringBuilder("|");
		for (int c = 0; c < columns; c++) {
			md.append(" h |");
		}
		md.append("\n|");
		for (int c = 0; c < columns; c++) {
			md.append(" --- |");
		}
		md.append('\n');
		for (int r = 0; r < rows; r++) {
			md.append("| x |\n");
		}
		return md.toString();
	}

	/** The rectangle every table in [blocks] adds up to — what a renderer holds. */
	private static int cellsOf(List<ExportBlock> blocks) {
		int cells = 0;
		for (ExportBlock block : blocks) {
			if (block instanceof ExportBlock.Table table) {
				int width = table.headers().size();
				for (List<String> row : table.rows()) {
					width = Math.max(width, row.size());
				}
				cells += width * (table.rows().size() + 1);
			}
		}
		return cells;
	}

	/**
	 * The one that took the heap out. Sixteen kilobytes of description — well
	 * inside every limit the write path enforces — became eight hundred thousand
	 * table cells, and the Word renderer ran the test JVM out of memory building
	 * them. What an export costs to render has to stay proportionate to what the
	 * description cost to write, or one field is a denial of service.
	 */
	@Test
	void aWideTableCannotCostAThousandTimesWhatItTookToWrite() {
		String markdown = wideTable(400, 2_000);
		assertThat(markdown.length()).as("a small description").isLessThan(20_000);

		List<ExportBlock> blocks = MarkdownBlocks.of(markdown);

		assertThat(cellsOf(blocks)).as("cells the renderers must lay out")
				.isPositive().isLessThanOrEqualTo(4_000);
		// And the two renderers that pad short rows produce a document rather than
		// an OutOfMemoryError.
		assertThat(new PdfIssueExportRenderer().render(export("t", blocks))).isNotEmpty();
		assertThat(new DocxIssueExportRenderer().render(export("t", blocks))).isNotEmpty();
	}

	/**
	 * The per-table ceiling is not the bound on its own: a maximal table is about
	 * two kilobytes of markdown, so a description can hold hundreds of them and
	 * pay the padding on every one. The budget is per document.
	 */
	@Test
	void manyTablesShareOneBudgetRatherThanEachGettingItsOwn() {
		StringBuilder markdown = new StringBuilder();
		for (int i = 0; i < 200; i++) {
			markdown.append(wideTable(40, 60)).append('\n');
		}

		List<ExportBlock> blocks = MarkdownBlocks.of(markdown.toString());

		assertThat(cellsOf(blocks)).isLessThanOrEqualTo(4_000);
	}

	/** A table small enough to be a table is still a table, with all of its rows. */
	@Test
	void anOrdinaryTableSurvivesUntouched() {
		List<ExportBlock> blocks = MarkdownBlocks.of("""
				| Format | Library |
				| --- | --- |
				| docx | POI |
				| pdf | openpdf |
				""");

		assertThat(blocks).singleElement()
				.isInstanceOfSatisfying(ExportBlock.Table.class, table -> {
					assertThat(table.headers()).containsExactly("Format", "Library");
					assertThat(table.rows()).hasSize(2);
				});
	}

	/**
	 * Control characters reach POI from a title nobody validated for them. Not a
	 * finding — POI substitutes them and both packages still open — but these are
	 * the two formats where an unrepresentable character would produce a file a
	 * reader silently rejects rather than an exception a log would show, so the
	 * behaviour is pinned rather than assumed.
	 */
	@Test
	void controlCharactersInATitleStillProduceOpenableOfficeFiles() throws Exception {
		IssueExport hostile = export("a\0b\7c\37d", List.of());

		try (XWPFDocument document = new XWPFDocument(
				new ByteArrayInputStream(new DocxIssueExportRenderer().render(hostile)))) {
			assertThat(document.getParagraphs()).isNotEmpty();
		}
		try (XSSFWorkbook workbook = new XSSFWorkbook(
				new ByteArrayInputStream(new XlsxIssueExportRenderer().render(hostile)))) {
			assertThat(workbook.getSheet("Fields")).isNotNull();
		}
	}

	// --- what gather() will and will not resolve -----------------------------

	private IssueService issues;
	private UserRepository users;
	private SprintRepository sprints;
	private AgileBoardRepository boards;
	private IssueCommentRepository comments;
	private IssueActivityRepository activities;
	private IssueExportService exports;
	private User caller;

	@BeforeEach
	void setUp() {
		issues = mock(IssueService.class);
		users = mock(UserRepository.class);
		IssueLinkService links = mock(IssueLinkService.class);
		comments = mock(IssueCommentRepository.class);
		activities = mock(IssueActivityRepository.class);
		ProjectRepository projects = mock(ProjectRepository.class);
		sprints = mock(SprintRepository.class);
		boards = mock(AgileBoardRepository.class);
		SettingsService settings = mock(SettingsService.class);

		when(links.linksOf(anyString(), any())).thenReturn(List.of());
		when(comments.findByIssueIdOrderByCreatedAtAsc(anyString(), any(Pageable.class)))
				.thenReturn(List.of());
		when(activities.findByIssueIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
				.thenReturn(Page.empty());
		when(projects.findById(anyString())).thenReturn(Optional.empty());
		when(settings.get()).thenReturn(new ServerSettings());

		caller = User.builder().id("caller").build();
		exports = new IssueExportService(issues, links, comments, activities, projects,
				sprints, boards, users, settings);
	}

	private static Issue issue() {
		return Issue.builder().id("own").projectId("open").readableId("HIN-50")
				.title("Export").build();
	}

	private static String valueOf(IssueExport export, String label) {
		return export.fields().stream().filter(field -> field.label().equals(label))
				.map(IssueExport.Field::value).findFirst().orElse(null);
	}

	/**
	 * {@code dependsOnIds} is stored exactly as it arrives — no membership check,
	 * no check that the id names an issue at all — so anyone who may edit one
	 * issue may point it at an id from a project that is closed to them.
	 * Resolving that id to a readable key would make the export an oracle for the
	 * keys, and so for the existence and the size, of projects the caller cannot
	 * open. It is the leak {@code IssueLinkService.linksOf} already refuses to be.
	 */
	@Test
	void dependsOnNeverNamesAnIssueTheCallerMayNotSee() {
		Issue own = issue();
		own.setDependsOnIds(List.of("secret", "visible"));
		Issue secret = Issue.builder().id("secret").projectId("closed")
				.readableId("CLSD-1").build();
		Issue visible = Issue.builder().id("visible").projectId("open")
				.readableId("HIN-7").build();
		when(issues.getForUser(anyString(), any())).thenReturn(own);
		when(issues.findOrNull("secret")).thenReturn(secret);
		when(issues.findOrNull("visible")).thenReturn(visible);
		when(issues.canAccess(secret, caller)).thenReturn(false);
		when(issues.canAccess(visible, caller)).thenReturn(true);

		IssueExport export = exports.gather("HIN-50", IssueExport.Options.standard(), caller);

		assertThat(valueOf(export, "Depends on")).isEqualTo("HIN-7");
		assertThat(new String(new XmlIssueExportRenderer().render(export), StandardCharsets.UTF_8))
				.doesNotContain("CLSD-1");
	}

	/**
	 * A parent has to live in its child's project when it is set, so this only
	 * bites where the write path cannot reach — a parent left behind in another
	 * project by a move. Its title is somebody else's sentence either way.
	 */
	@Test
	void aParentInAProjectTheCallerCannotOpenIsNotNamedEither() {
		Issue own = issue();
		own.setParentId("stranded");
		Issue parent = Issue.builder().id("stranded").projectId("closed")
				.readableId("CLSD-9").title("Salaries 2026").build();
		when(issues.getForUser(anyString(), any())).thenReturn(own);
		when(issues.findOrNull("stranded")).thenReturn(parent);
		when(issues.canAccess(parent, caller)).thenReturn(false);

		IssueExport export = exports.gather("HIN-50", IssueExport.Options.standard(), caller);

		assertThat(valueOf(export, "Parent")).isEmpty();
	}

	/**
	 * {@code DirectoryUser} — what the rest of the platform hands out when it
	 * turns an id into somebody to look at — carries username, display name,
	 * avatar and title, and deliberately not the address. A document that fell
	 * back to the address would carry the mail addresses of everyone who ever
	 * touched a ticket out of the platform, into somebody's Downloads folder.
	 */
	@Test
	void aUserWithoutADisplayNameIsNamedByUsernameAndNeverByEmail() {
		Issue own = issue();
		own.setReporterId("quiet");
		User quiet = User.builder().id("quiet").username("tomas")
				.email("tomas@asta.example").build();
		when(issues.getForUser(anyString(), any())).thenReturn(own);
		// Names arrive in one batched read now, so that is the call to answer.
		when(users.findAllById(anyIterable())).thenReturn(List.of(quiet));

		IssueExport export = exports.gather("HIN-50", IssueExport.Options.standard(), caller);

		assertThat(valueOf(export, "Reporter")).isEqualTo("tomas");
		assertThat(new String(new XmlIssueExportRenderer().render(export), StandardCharsets.UTF_8))
				.doesNotContain("tomas@asta.example").doesNotContain("@");
	}

	/**
	 * A sprint id is stored the way it arrives too, so the same oracle exists one
	 * field along: point an issue you own at a sprint from a board that spans a
	 * project you were removed from, and a name comes back. An id keeps a
	 * revocation honest only for as long as nothing resolves it afterwards.
	 */
	@Test
	void aSprintFromABoardThatDoesNotSpanThisProjectIsNotNamed() {
		Issue own = issue();
		own.setSprintId("elsewhere");
		when(issues.getForUser(anyString(), any())).thenReturn(own);
		when(sprints.findById("elsewhere")).thenReturn(Optional.of(
				Sprint.builder().id("elsewhere").boardId("board").name("Payroll Q4").build()));
		when(boards.findById("board")).thenReturn(Optional.of(
				AgileBoard.builder().id("board").projectIds(List.of("closed")).build()));

		IssueExport export = exports.gather("HIN-50", IssueExport.Options.standard(), caller);

		assertThat(valueOf(export, "Sprint")).isEmpty();
	}

	/** The sprint of a board that does span it is named, as it always was. */
	@Test
	void aSprintOfThisProjectsOwnBoardIsNamedAsBefore() {
		Issue own = issue();
		own.setSprintId("ours");
		when(issues.getForUser(anyString(), any())).thenReturn(own);
		when(sprints.findById("ours")).thenReturn(Optional.of(
				Sprint.builder().id("ours").boardId("board").name("Sprint 7").build()));
		when(boards.findById("board")).thenReturn(Optional.of(
				AgileBoard.builder().id("board").projectIds(List.of("open")).build()));

		IssueExport export = exports.gather("HIN-50", IssueExport.Options.standard(), caller);

		assertThat(valueOf(export, "Sprint")).isEqualTo("Sprint 7");
	}

	// --- what gather() costs ------------------------------------------------

	/**
	 * A document that names forty people reads the directory once.
	 *
	 * <p>The number pinned here is the round trips, not the milliseconds. Names
	 * used to be resolved one id at a time, so a busy ticket cost one sequential
	 * read per distinct person in it — forty of them is forty times the latency
	 * between this process and the database, spent on a request thread while
	 * somebody waits for a file. Written down because nothing else would notice
	 * it going back: an export resolved lazily produces exactly the same document.
	 */
	@Test
	void everyNameInADocumentIsReadInOneQuery() {
		Issue own = issue();
		own.setReporterId("u0");
		own.setAssigneeIds(List.of("u1", "u2"));
		List<IssueComment> thread = new ArrayList<>();
		List<IssueActivity> history = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			thread.add(IssueComment.builder().id("c" + i).issueId("own")
					.authorId("u" + (i % 20)).text("noted").build());
			history.add(IssueActivity.builder().id("a" + i).issueId("own")
					.actorId("u" + (20 + i % 20)).field(IssueActivity.Field.CREATED).build());
		}
		when(issues.getForUser(anyString(), any())).thenReturn(own);
		when(comments.findByIssueIdOrderByCreatedAtAsc(anyString(), any(Pageable.class)))
				.thenReturn(thread);
		when(activities.findByIssueIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
				.thenReturn(new PageImpl<>(history));

		IssueExport export = exports.gather("HIN-50",
				new IssueExport.Options(true, true, true, true), caller);

		assertThat(export.comments()).hasSize(200);
		assertThat(export.activity()).hasSize(200);
		verify(users, times(1)).findAllById(anyIterable());
		verify(users, never()).findById(anyString());
	}
}
