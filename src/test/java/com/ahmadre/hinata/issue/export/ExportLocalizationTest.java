package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueActivity;
import com.ahmadre.hinata.issue.IssueActivityRepository;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueLinkService;
import com.ahmadre.hinata.issue.IssueLinkType;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether an exported issue reads as a document written for the person who asked
 * for it: in their language, on their clock, naming the people and things it
 * refers to.
 *
 * <p>All three were wrong in 10.2.0 and all three were wrong in the same way —
 * the export was written for the server rather than for a reader. The labels
 * were English literals in the code, the timestamps were the process's own UTC,
 * and a mention arrived as {@code {{user:6a3d0d…}}}, which is an id the reader
 * has no way to turn into a person.
 */
class ExportLocalizationTest {

	private IssueService issues;
	private IssueLinkService links;
	private IssueCommentRepository comments;
	private IssueActivityRepository activities;
	private UserRepository users;
	private IssueExportService exports;
	private User caller;

	/** 2026-08-18 16:39 UTC — the "Created" stamp from the reported export. */
	private static final Instant CREATED = Instant.parse("2026-08-18T16:39:00Z");

	@BeforeEach
	void setUp() {
		issues = mock(IssueService.class);
		links = mock(IssueLinkService.class);
		comments = mock(IssueCommentRepository.class);
		activities = mock(IssueActivityRepository.class);
		users = mock(UserRepository.class);
		ProjectRepository projects = mock(ProjectRepository.class);
		SprintRepository sprints = mock(SprintRepository.class);
		AgileBoardRepository boards = mock(AgileBoardRepository.class);
		SettingsService settings = mock(SettingsService.class);
		BrandLogoService brandLogo = mock(BrandLogoService.class);

		when(links.linksOf(anyString(), any())).thenReturn(List.of());
		when(comments.findByIssueIdOrderByCreatedAtAsc(anyString(), any(Pageable.class)))
				.thenReturn(List.of());
		when(activities.findByIssueIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
				.thenReturn(Page.empty());
		when(projects.findById(anyString())).thenReturn(Optional.empty());
		when(settings.get()).thenReturn(new ServerSettings());
		when(brandLogo.raster()).thenReturn(Optional.empty());
		when(users.findAllById(any())).thenReturn(List.of());
		when(issues.getForUser(anyString(), any())).thenReturn(issue());

		caller = User.builder().id("caller").build();
		exports = new IssueExportService(issues, links, comments, activities, projects,
				sprints, boards, users, settings, brandLogo);
	}

	private static Issue issue() {
		Issue issue = Issue.builder().id("own").projectId("open").readableId("HIN-50")
				.title("Export").type(Issue.Type.SUBTASK).priority(Issue.Priority.SHOWSTOPPER)
				.state("In Progress").build();
		issue.setCreatedAt(CREATED);
		issue.setDueDate(LocalDate.of(2026, 9, 1));
		return issue;
	}

	private IssueExport gather(ExportWords words) {
		return exports.gather("HIN-50", IssueExport.Options.standard(), caller, words);
	}

	private static String labelOf(IssueExport export, String key) {
		return export.fields().stream().filter(f -> f.key().equals(key))
				.map(IssueExport.Field::label).findFirst().orElse(null);
	}

	private static String valueOf(IssueExport export, String key) {
		return export.fields().stream().filter(f -> f.key().equals(key))
				.map(IssueExport.Field::value).findFirst().orElse(null);
	}

	// --- language ------------------------------------------------------------

	@Test
	void fieldLabelsAreWrittenInTheReadersLanguage() {
		assertThat(labelOf(gather(ExportWordsFixture.english()), "dueDate")).isEqualTo("Due date");
		assertThat(labelOf(gather(ExportWordsFixture.german()), "dueDate"))
				.isEqualTo("Fälligkeitsdatum");
	}

	/**
	 * A priority is an enum, so without a translation it printed the constant —
	 * "SHOWSTOPPER" is not a word in English either.
	 */
	@Test
	void enumValuesAreTranslatedRatherThanPrintedAsConstants() {
		IssueExport german = gather(ExportWordsFixture.german());

		assertThat(valueOf(german, "priority")).isEqualTo("Blockierend");
		assertThat(valueOf(german, "type")).isEqualTo("Unteraufgabe");
	}

	/**
	 * The one value that is deliberately not translated. Workflow states are named
	 * per project by the people who work in it, so a state is content — translating
	 * "In Progress" would mean overwriting somebody's own vocabulary.
	 */
	@Test
	void theWorkflowStateIsLeftAsTheProjectNamedIt() {
		assertThat(valueOf(gather(ExportWordsFixture.german()), "status"))
				.isEqualTo("In Progress");
	}

	@Test
	void sectionHeadingsAndTableHeadersAreTranslated() {
		IssueExport german = gather(ExportWordsFixture.german());

		assertThat(german.words().t("export.section.attachments")).isEqualTo("Anhänge");
		assertThat(german.words().t("export.section.comments", 3)).isEqualTo("Kommentare (3)");
		assertThat(german.words().t("export.column.uploadedBy")).isEqualTo("Hochgeladen von");
	}

	@Test
	void linkVerbsAreTranslatedPerTypeAndDirection() {
		Issue other = Issue.builder().id("other").readableId("HIN-7").title("Andere").build();
		when(links.linksOf(anyString(), any())).thenReturn(List.of(
				new IssueLinkService.LinkView("l1", IssueLinkType.BLOCKS, false, "is blocked by",
						other)));

		IssueExport german = gather(ExportWordsFixture.german());

		assertThat(german.links()).singleElement()
				.extracting(IssueExport.Link::verb).isEqualTo("wird blockiert von");
	}

	// --- time ----------------------------------------------------------------

	/**
	 * The complaint, exactly: a German reader was handed "2026-08-18 16:39 UTC",
	 * which is neither their clock nor their spelling of a date.
	 */
	@Test
	void timestampsAreWrittenOnTheReadersClock() {
		String german = valueOf(gather(ExportWordsFixture.german()), "created");

		// 16:39 UTC is 18:39 in Berlin in August, and the zone is named so the
		// reader can tell which clock it is.
		assertThat(german).contains("18:39").contains("18.08.2026").contains("MESZ");
		assertThat(german).doesNotContain("UTC");
	}

	@Test
	void theSameInstantReadsDifferentlyInADifferentZone() {
		String berlin = valueOf(gather(ExportWordsFixture.german()), "created");
		String tokyo = valueOf(
				gather(ExportWordsFixture.of(Locale.ENGLISH, ZoneId.of("Asia/Tokyo"))), "created");

		assertThat(berlin).isNotEqualTo(tokyo);
		// 16:39 UTC on the 18th is 01:39 on the 19th in Tokyo — the date moves too,
		// which is the whole reason a fixed offset would not have been enough.
		assertThat(tokyo).contains("Aug 19, 2026").contains("1:39");
	}

	/**
	 * A due date has no time and therefore no zone. Shifting one is how a deadline
	 * silently moves by a day for readers east or west of the server.
	 */
	@Test
	void dateOnlyValuesAreFormattedButNeverShifted() {
		assertThat(valueOf(gather(ExportWordsFixture.german()), "dueDate")).isEqualTo("01.09.2026");
		assertThat(valueOf(
				gather(ExportWordsFixture.of(Locale.ENGLISH, ZoneId.of("Pacific/Kiritimati"))),
				"dueDate")).isEqualTo("Sep 1, 2026");
	}

	/** The XML is read by a program, so its stamps stay machine-shaped. */
	@Test
	void theXmlStampsAreIsoWithTheReadersOffset() {
		String xml = new String(new XmlIssueExportRenderer()
				.render(gather(ExportWordsFixture.german())), StandardCharsets.UTF_8);

		// The machine-readable stamps carry an offset. The <field> values do not:
		// those are the same display strings the PDF prints, localized spelling
		// and all, which is what a `label` attribute beside a stable `name` is
		// there to make legible.
		assertThat(xml).contains("<generatedAt>").contains("+02:00");
		assertThat(xml).doesNotContain("<generatedAt>2026-08-27T00");
		assertThat(xml.substring(xml.indexOf("<generatedAt>"),
				xml.indexOf("</generatedAt>"))).doesNotContain("MESZ");
	}

	// --- smart links ---------------------------------------------------------

	private void describedBy(String storedDoc, String plain) {
		Issue issue = issue();
		issue.setDescriptionDoc(storedDoc);
		issue.setDescription(plain);
		when(issues.getForUser(anyString(), any())).thenReturn(issue);
	}

	private static String descriptionOf(IssueExport export) {
		StringBuilder text = new StringBuilder();
		for (ExportBlock block : export.description()) {
			if (block instanceof ExportBlock.Paragraph paragraph) {
				text.append(ExportBlock.Span.plain(paragraph.spans())).append('\n');
			}
			else if (block instanceof ExportBlock.Code code) {
				text.append(code.text()).append('\n');
			}
		}
		return text.toString();
	}

	/** A mention resolves to the name, and the name is current rather than stored. */
	@Test
	void aMentionIsPrintedAsThePersonItNames() {
		describedBy(null, "{{user:6a3d0d9774fd69a89d133e9c}} has every access already.");
		when(users.findAllById(any())).thenReturn(List.of(
				User.builder().id("6a3d0d9774fd69a89d133e9c").displayName("Rebar Ahmad").build()));

		assertThat(descriptionOf(gather(ExportWordsFixture.english())))
				.contains("@Rebar Ahmad has every access already.")
				.doesNotContain("6a3d0d9774fd69a89d133e9c");
	}

	/**
	 * A person who has since been deleted has no name to print, and the reference
	 * is left as it stands. A sentence missing its subject would be worse than one
	 * carrying an id.
	 */
	@Test
	void anUnresolvableMentionKeepsItsToken() {
		describedBy(null, "{{user:deadbeef}} left.");

		assertThat(descriptionOf(gather(ExportWordsFixture.english())))
				.contains("{{user:deadbeef}} left.");
	}

	/** An issue token already carries the key; the stored label adds the title. */
	@Test
	void anIssueReferenceKeepsItsKeyAndGainsItsTitle() {
		describedBy("""
				{"root":{"type":"root","children":[{"type":"paragraph","children":[
				{"type":"smartlink","kind":"issue","targetId":"HIN-5","label":"Blue-green deploys"}
				]}]}}""", "see {{issue:HIN-5}}");

		assertThat(descriptionOf(gather(ExportWordsFixture.english())))
				.contains("HIN-5 (Blue-green deploys)");
	}

	/**
	 * An article id says nothing at all, so the label stored beside it is the whole
	 * value. Without one the reference is named for what it is — the title is
	 * never looked up, because an export must not answer questions about articles
	 * its reader may not be allowed to open.
	 */
	@Test
	void anArticleReferenceUsesTheLabelStoredBesideIt() {
		describedBy("""
				{"root":{"type":"root","children":[{"type":"paragraph","children":[
				{"type":"smartlink","kind":"doc","targetId":"a1","label":"Release-Ablauf"}
				]}]}}""", "in {{doc:a1}}");

		assertThat(descriptionOf(gather(ExportWordsFixture.german())))
				.contains("Release-Ablauf").doesNotContain("a1");
	}

	/**
	 * And when there is no label — a description written before the editor stored
	 * one, or a plain-text one that never had a document at all — the reference is
	 * named for what it is. The id is never printed: it says nothing to a reader,
	 * and the title is not looked up because an export must not answer questions
	 * about articles its reader may not be allowed to open.
	 */
	@Test
	void anArticleReferenceWithoutALabelIsNamedForWhatItIs() {
		describedBy(null, "steht in {{doc:6a4fbbe62ce74c7b4c60832f}}");

		assertThat(descriptionOf(gather(ExportWordsFixture.german())))
				.contains("steht in eine Wissensseite")
				.doesNotContain("6a4fbbe62ce74c7b4c60832f");
	}

	/**
	 * Resolved after the markdown is parsed, never before. A display name is
	 * content: substituting "A*B*C" into markdown first would have turned the rest
	 * of the paragraph italic.
	 */
	@Test
	void aNameContainingMarkdownStaysText() {
		describedBy(null, "{{user:x}} wrote *this*.");
		when(users.findAllById(any())).thenReturn(List.of(
				User.builder().id("x").displayName("A*B*C").build()));

		assertThat(descriptionOf(gather(ExportWordsFixture.english())))
				.contains("@A*B*C wrote this.");
	}

	/** A fenced block is quoted verbatim, tokens included. */
	@Test
	void aTokenInsideACodeBlockIsLeftAlone() {
		describedBy(null, "```\n{{user:x}}\n```");
		when(users.findAllById(any())).thenReturn(List.of(
				User.builder().id("x").displayName("Rebar").build()));

		assertThat(descriptionOf(gather(ExportWordsFixture.english())))
				.contains("{{user:x}}").doesNotContain("@Rebar");
	}

	/** Comments carry mentions as readily as the description does. */
	@Test
	void mentionsInCommentsAreResolvedToo() {
		when(issues.getForUser(anyString(), any())).thenReturn(issue());
		IssueComment comment = IssueComment.builder().id("c1").issueId("own")
				.authorId("author").createdAt(CREATED).text("ping {{user:mentioned}}").build();
		when(comments.findByIssueIdOrderByCreatedAtAsc(anyString(), any(Pageable.class)))
				.thenReturn(List.of(comment));
		when(users.findAllById(any())).thenReturn(List.of(
				User.builder().id("mentioned").displayName("Lena").build()));

		IssueExport export = exports.gather("HIN-50", IssueExport.Options.standard(), caller,
				ExportWordsFixture.english());

		assertThat(ExportBlock.Span.plain(
				((ExportBlock.Paragraph) export.comments().getFirst().body().getFirst()).spans()))
				.isEqualTo("ping @Lena");
	}

	/** The history reuses the field labels the table above it already prints. */
	@Test
	void theHistoryNamesFieldsTheSameWayTheDetailsTableDoes() {
		when(issues.getForUser(anyString(), any())).thenReturn(issue());
		IssueActivity entry = IssueActivity.builder().id("a1").issueId("own")
				.actorId("actor").createdAt(CREATED).field(IssueActivity.Field.DUE_DATE)
				.fromValue("2026-08-01").toValue("2026-09-01").build();
		when(activities.findByIssueIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(entry)));

		IssueExport german = exports.gather("HIN-50",
				new IssueExport.Options(true, true, true, true), caller,
				ExportWordsFixture.german());

		assertThat(german.activity()).singleElement()
				.extracting(IssueExport.Activity::what)
				.asString().startsWith("Fälligkeitsdatum:");
	}
}
