package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.moderation.image.ImageTierState;
import com.ahmadre.hinata.moderation.report.ContentReport;
import com.ahmadre.hinata.moderation.report.ContentReportService;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The moderator's two work queues, read and decided.
 *
 * <p>Both halves of moderation land here: {@link ModerationRecord}, which is what
 * the automated gate concluded, and {@link ContentReport}, which is what a colleague
 * noticed. They stay separate collections because they are separate kinds of claim —
 * a score is evidence, a report is an assertion — but they are answered by the same
 * person in the same sitting, so they are described with one vocabulary here
 * ({@link ModerationSurface}, {@link ModerationCategory}) rather than making an admin
 * hold two taxonomies in their head.
 *
 * <h2>Why the rows are assembled instead of returned</h2>
 *
 * <p>Neither collection stores a copy of what it is about — that is the rule
 * {@link ModerationRecorder} is built on — so a raw row is a handful of ids. A queue
 * of ids is a queue nobody works: the moderator cannot tell whose comment it is, in
 * which project, or how to open it, and every judgement then starts with three
 * lookups they do by hand. This service does those lookups once per page, batched, so
 * a page of twenty-five rows costs a fixed handful of queries rather than one per row.
 *
 * <p>What is <em>not</em> assembled is the content itself. The row says where the
 * item is; the moderator opens it and reads it in context, which is also the only way
 * a comment can be judged fairly — a single message pulled out of the conversation it
 * answers is exactly how a reasonable remark looks like an unprovoked one.
 */
@Service
@RequiredArgsConstructor
public class ModerationQueueService {

	/**
	 * Hard ceiling on a requested page. The queue is an infinite-scrolling list with a
	 * fixed client page size; a larger number can only come from a hand-written call,
	 * and every row here costs cross-collection lookups.
	 */
	public static final int MAX_PAGE_SIZE = 100;

	/** A queue is only ever read newest first — the oldest end is decided history. */
	private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "createdAt");

	private final ModerationRecordRepository records;
	private final ContentReportService reports;
	private final ProjectRepository projects;
	private final UserRepository users;
	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final ArticleRepository articles;
	private final AuditService audit;
	private final ModerationService moderation;

	// --- Rows -----------------------------------------------------------------

	/**
	 * One recorded verdict, ready to render.
	 *
	 * @param category the strongest category that fired, which is the one a refusal
	 *                 named — every match is kept on the record, but a list row shows
	 *                 the reason the decision was actually made on
	 * @param evidence which rule fired, for the reviewing admin only. It never leaves
	 *                 this endpoint: handed to an author it turns the filter into a
	 *                 search interface for the ruleset
	 * @param link     in-app route that opens the item, or {@code null} when it cannot
	 *                 be addressed
	 * @param note     an earlier reviewer's reasoning
	 */
	public record RecordRow(String id, Instant createdAt, ModerationSurface surface,
			ModerationCategory category, ModerationDecision decision,
			ModerationVerdict.ModerationTier tier, int score, boolean degraded,
			ModerationRecord.ReviewState reviewState, String targetType, String targetId,
			String label, String link, String projectId, String projectName,
			String authorId, String authorName, String evidence, String note) {
	}

	/**
	 * One user's report, ready to render.
	 *
	 * @param category the policy category the reporter picked, {@code null} for a
	 *                 report filed as "other" — forcing a reporter to name a policy
	 *                 label they do not know is how reports stop being filed at all
	 * @param surface  the reported target expressed as a moderation surface, so both
	 *                 queues read in one vocabulary
	 * @param reason   the reporter's own words
	 * @param authorId who wrote the reported content, not who reported it
	 * @param note     the handling moderator's reasoning
	 */
	public record ReportRow(String id, Instant createdAt, ContentReport.State state,
			ModerationCategory category, ModerationSurface surface, String reason,
			String reporterId, String reporterName, String targetType, String targetId,
			String label, String link, String projectId, String projectName,
			String authorId, String authorName, String note) {
	}

	/**
	 * The open backlog for the badge on the admin section, plus what the image tier
	 * is actually doing.
	 *
	 * <p>[imageTier] rides along here rather than getting an endpoint of its own
	 * because the panel that shows the backlog is the panel that shows the policy
	 * switches, and the switch labelled "classify images" is precisely the one an
	 * operator can otherwise read as a promise the product is not keeping.
	 */
	public record Summary(long openRecords, long openReports, long open, ImageTierState imageTier) {
	}

	// --- Records --------------------------------------------------------------

	/**
	 * One page of recorded verdicts. Every filter is optional; {@code null} widens.
	 *
	 * <p>The unfiltered read — one review state, newest first — goes through the
	 * derived method that {@code review_created} serves end to end, because that is
	 * the query run every single time the queue is opened. Only once a moderator
	 * narrows does this fall through to {@link ModerationRecordRepository#search},
	 * which scans; that trade is spelled out on the repository and it holds because a
	 * clean verdict is never recorded at all, so the collection stays small.
	 */
	public Page<RecordRow> listRecords(ModerationRecord.ReviewState state, ModerationSurface surface,
			ModerationCategory category, String projectId, String authorId, int page, int size) {
		int number = Math.max(0, page);
		int perPage = Math.clamp(size, 1, MAX_PAGE_SIZE);
		Page<ModerationRecord> found;
		if (surface == null && category == null && isBlank(projectId) && isBlank(authorId)) {
			found = state == null
					? records.findAll(PageRequest.of(number, perPage, NEWEST))
					: records.findByReviewStateOrderByCreatedAtDesc(state,
							PageRequest.of(number, perPage));
		}
		else {
			found = records.search(state, surface, category, trimmed(projectId), trimmed(authorId),
					null, null, PageRequest.of(number, perPage, NEWEST));
		}
		return enrichRecords(found);
	}

	/**
	 * Files a human decision against verdict [id].
	 *
	 * <p>Only the review half of a record is ever written. The verdict itself is what
	 * the machine decided under the thresholds in force at the time, and a moderator
	 * agreeing or disagreeing must not be able to rewrite it — the gap between the two
	 * is the only number that says whether the thresholds are set right.
	 *
	 * <p>Deciding again is allowed, for the reason
	 * {@link ContentReportService#handle} gives: a decision that cannot be revised
	 * forces the revision to happen where nobody can see it. The row keeps the latest
	 * decision, the audit log keeps all of them.
	 *
	 * @throws ApiException 400 when [state] would leave the record open, 404 when the
	 *                      record no longer exists
	 */
	public RecordRow review(User moderator, String id, ModerationRecord.ReviewState state,
			String note) {
		if (state == null || state == ModerationRecord.ReviewState.OPEN) {
			throw ApiException.badRequest("error.moderation.decisionRequired");
		}
		ModerationRecord record = records.findById(id)
				.orElseThrow(() -> ApiException.notFound("moderationRecord"));
		record.setReviewState(state);
		record.setReviewedBy(moderator.getId());
		record.setReviewedAt(Instant.now());
		record.setReviewNote(ContentReportService.trimNote(note));
		ModerationRecord saved = records.save(record);
		ModerationRecord.CategoryScore primary = primary(saved);
		audit.event(AuditAction.MODERATION_RECORD_REVIEWED)
				.actor(moderator)
				.target(saved.getId(), saved.getLabel())
				.meta("state", state.name())
				.meta("surface", saved.getSurface() == null ? null : saved.getSurface().name())
				.meta("decision", saved.getDecision() == null ? null : saved.getDecision().name())
				.meta("category", primary == null || primary.getCategory() == null
						? null : primary.getCategory().name())
				.log();
		// Through the same assembly a page of twenty-five goes through, rather than a
		// second single-row mapper: that duplicate is the kind that drifts, and the
		// queue would render one thing on load and another the moment a row was decided.
		return enrichRecords(new PageImpl<>(List.of(saved))).getContent().getFirst();
	}

	// --- Reports --------------------------------------------------------------

	/** One page of user reports, newest first. A {@code null} [state] widens it to all. */
	public Page<ReportRow> listReports(ContentReport.State state, int page, int size) {
		return enrichReports(reports.list(state,
				PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE))));
	}

	/** Closes report [id]; see {@link ContentReportService#handle} for what that means. */
	public ReportRow handleReport(User moderator, String id, ContentReport.State state, String note) {
		return enrichReports(new PageImpl<>(List.of(reports.handle(moderator, id, state, note))))
				.getContent().getFirst();
	}

	// --- Summary --------------------------------------------------------------

	/** What is still waiting on a human, in both queues. */
	public Summary summary() {
		long openRecords = records.countByReviewState(ModerationRecord.ReviewState.OPEN);
		long openReports = reports.countOpen();
		return new Summary(openRecords, openReports, openRecords + openReports,
				moderation.imageTierState());
	}

	// --- Assembly -------------------------------------------------------------

	/**
	 * Resolves the names and routes a page of verdicts needs, in one batch per
	 * collection.
	 */
	private Page<RecordRow> enrichRecords(Page<ModerationRecord> found) {
		List<ModerationRecord> rows = found.getContent();
		Map<String, String> projectNames = projectNames(
				rows.stream().map(ModerationRecord::getProjectId).toList());
		Map<String, String> userNames = userNames(
				rows.stream().map(ModerationRecord::getAuthorId).toList());
		Map<String, String> commentLinks = commentLinks(rows);
		return found.map(record -> {
			ModerationRecord.CategoryScore primary = primary(record);
			return new RecordRow(record.getId(), record.getCreatedAt(), record.getSurface(),
					primary == null ? null : primary.getCategory(), record.getDecision(),
					record.getTier(), primary == null ? 0 : primary.getScore(), record.isDegraded(),
					record.getReviewState(), record.getTargetType(), record.getTargetId(),
					record.getLabel(), commentLinks.get(record.getId()), record.getProjectId(),
					named(projectNames, record.getProjectId()), record.getAuthorId(),
					named(userNames, record.getAuthorId()),
					primary == null ? null : primary.getEvidence(), record.getReviewNote());
		});
	}

	/**
	 * The same for reports, in two passes: the reported items first, then the accounts
	 * they name. The second pass cannot be merged into the first — who wrote a
	 * reported comment is only known once the comment has been loaded.
	 */
	private Page<ReportRow> enrichReports(Page<ContentReport> found) {
		List<ContentReport> rows = found.getContent();
		Map<String, Issue> issuesById = byId(
				issues.findAllById(idsOf(rows, ModerationQueueService::issueIdOf)), Issue::getId);
		Map<String, Article> articlesById = byId(articles.findAllById(
				idsOf(rows, report -> typeIs(report, ContentReport.TargetType.ARTICLE)
						? report.getTargetId() : null)), Article::getId);
		Map<String, IssueComment> commentsById = byId(comments.findAllById(
				idsOf(rows, report -> typeIs(report, ContentReport.TargetType.COMMENT)
						? report.getTargetId() : null)), IssueComment::getId);

		Map<String, Reference> references = new HashMap<>();
		Set<String> people = new LinkedHashSet<>();
		for (ContentReport report : rows) {
			Reference reference = reference(report, issuesById, articlesById, commentsById);
			references.put(report.getId(), reference);
			if (reference.authorId() != null) {
				people.add(reference.authorId());
			}
			if (report.getReporterId() != null) {
				people.add(report.getReporterId());
			}
		}
		Map<String, String> userNames = userNames(people);
		Map<String, String> projectNames = projectNames(
				rows.stream().map(ContentReport::getProjectId).toList());

		return found.map(report -> {
			Reference reference = references.get(report.getId());
			String authorName = named(userNames, reference.authorId());
			return new ReportRow(report.getId(), report.getCreatedAt(), report.getState(),
					report.getReason() == null ? null : report.getReason().category(),
					surfaceOf(report.getTargetType()), report.getNote(), report.getReporterId(),
					named(userNames, report.getReporterId()), targetTypeOf(report.getTargetType()),
					report.getTargetId(),
					// A reported person is named by their own account, which is only
					// loaded in the second pass — so it arrives as the author name and
					// is the same string the row's author chip shows.
					typeIs(report, ContentReport.TargetType.USER) ? authorName : reference.label(),
					reference.link(), report.getProjectId(),
					named(projectNames, report.getProjectId()), reference.authorId(), authorName,
					report.getHandlerNote());
		});
	}

	/**
	 * What a report points at: how to name it, how to open it, and who wrote it.
	 * Deliberately resolved once rather than as three switches over the target type,
	 * which is the shape that lets one of them quietly disagree with the others.
	 */
	private record Reference(String label, String link, String authorId) {

		private static final Reference NONE = new Reference(null, null, null);
	}

	private Reference reference(ContentReport report, Map<String, Issue> issuesById,
			Map<String, Article> articlesById, Map<String, IssueComment> commentsById) {
		if (report.getTargetType() == null) {
			return Reference.NONE;
		}
		return switch (report.getTargetType()) {
			case ISSUE -> {
				Issue issue = issuesById.get(report.getTargetId());
				yield issue == null ? Reference.NONE
						: new Reference(issue.getReadableId(), issueLink(issue), issue.getReporterId());
			}
			case COMMENT -> {
				Issue issue = issuesById.get(report.getContextId());
				IssueComment comment = commentsById.get(report.getTargetId());
				yield new Reference(issue == null ? null : issue.getReadableId(),
						issue == null ? null : commentLink(issue, report.getTargetId()),
						comment == null ? null : comment.getAuthorId());
			}
			case ARTICLE -> {
				Article article = articlesById.get(report.getTargetId());
				yield article == null ? Reference.NONE
						: new Reference(article.getTitle(), "/knowledge/" + article.getId(),
								article.getAuthorId());
			}
			case ATTACHMENT -> {
				Issue issue = issuesById.get(report.getContextId());
				Issue.Attachment attachment = attachment(issue, report.getTargetId());
				yield new Reference(attachment == null ? null : attachment.getFileName(),
						issue == null ? null : issueLink(issue),
						attachment == null ? null : attachment.getUploaderId());
			}
			// A reported person is their own author: the report is about a pattern of
			// behaviour rather than one item, which is exactly why it has no content id.
			case USER -> new Reference(null, "/admin/users?user=" + report.getTargetId(),
					report.getTargetId());
		};
	}

	/**
	 * Routes for the flagged comments on this page.
	 *
	 * <p>The only target the client cannot address on its own: a comment lives inside
	 * a thread and the record stores no issue, so without this every flagged comment —
	 * the most common flagged surface there is — would be a row with no way to open
	 * the thing it is about. Issues and articles are addressable from the record alone
	 * and are left to the client.
	 */
	private Map<String, String> commentLinks(List<ModerationRecord> rows) {
		Set<String> commentIds = new LinkedHashSet<>();
		for (ModerationRecord record : rows) {
			if ("comment".equals(record.getTargetType()) && record.getTargetId() != null) {
				commentIds.add(record.getTargetId());
			}
		}
		if (commentIds.isEmpty()) {
			return Map.of();
		}
		Map<String, IssueComment> commentsById =
				byId(comments.findAllById(commentIds), IssueComment::getId);
		Map<String, Issue> issuesById = byId(issues.findAllById(distinct(commentsById.values().stream()
				.map(IssueComment::getIssueId)
				.toList())), Issue::getId);
		Map<String, String> links = new HashMap<>();
		for (ModerationRecord record : rows) {
			IssueComment comment = commentsById.get(record.getTargetId());
			Issue issue = comment == null ? null : issuesById.get(comment.getIssueId());
			if (issue != null) {
				links.put(record.getId(), commentLink(issue, record.getTargetId()));
			}
		}
		return links;
	}

	// --- Lookups --------------------------------------------------------------

	/**
	 * Name for [id], or {@code null} when there is no id to name. Every row here can
	 * legitimately have no project and no author — refusals carry neither — and a bare
	 * {@code map.get(null)} would be an NPE on the empty maps those pages produce,
	 * which is a crash in the queue rather than a missing chip on one row.
	 */
	private static String named(Map<String, String> names, String id) {
		return id == null ? null : names.get(id);
	}

	private Map<String, String> projectNames(Collection<String> ids) {
		Set<String> wanted = distinct(ids);
		if (wanted.isEmpty()) {
			return Map.of();
		}
		Map<String, String> names = new HashMap<>();
		projects.findAllById(wanted).forEach(project -> names.put(project.getId(), project.getName()));
		return names;
	}

	/**
	 * Display names for the accounts a page names. Falls back to the e-mail the same
	 * way {@link AuditService} does — a row reading "unknown" for an account that
	 * exists and simply never set a display name sends a moderator hunting.
	 */
	private Map<String, String> userNames(Collection<String> ids) {
		Set<String> wanted = distinct(ids);
		if (wanted.isEmpty()) {
			return Map.of();
		}
		Map<String, String> names = new HashMap<>();
		users.findAllById(wanted).forEach(user -> names.put(user.getId(),
				user.getDisplayName() != null ? user.getDisplayName() : user.getEmail()));
		return names;
	}

	private static String issueIdOf(ContentReport report) {
		if (report.getTargetType() == null) {
			return null;
		}
		return switch (report.getTargetType()) {
			case ISSUE -> report.getTargetId();
			// A comment and an attachment are both located by the issue they live in,
			// which the report carries precisely because neither can be found without it.
			case COMMENT, ATTACHMENT -> report.getContextId();
			default -> null;
		};
	}

	private static Issue.Attachment attachment(Issue issue, String attachmentId) {
		if (issue == null || issue.getAttachments() == null || attachmentId == null) {
			return null;
		}
		return issue.getAttachments().stream()
				.filter(candidate -> attachmentId.equals(candidate.getId()))
				.findFirst()
				.orElse(null);
	}

	/**
	 * The strongest category on a record — the same rule
	 * {@link ModerationVerdict#primary()} applies, so the queue row names the category
	 * the author was told about rather than an arbitrary first hit. Ties break towards
	 * the category declared first in {@link ModerationCategory}, which is ordered most
	 * to least severe.
	 */
	private static ModerationRecord.CategoryScore primary(ModerationRecord record) {
		List<ModerationRecord.CategoryScore> scores = record.getCategories();
		if (scores == null || scores.isEmpty()) {
			return null;
		}
		return scores.stream()
				.filter(score -> score.getCategory() != null)
				.max((a, b) -> a.getScore() != b.getScore()
						? Integer.compare(a.getScore(), b.getScore())
						: Integer.compare(b.getCategory().ordinal(), a.getCategory().ordinal()))
				.orElse(null);
	}

	/**
	 * The reported target expressed as a moderation surface. A report about an issue
	 * is a report about its body, and a reported person is a report about their
	 * profile — the mapping exists so one filter and one set of labels serve both
	 * queues instead of the panel translating between two taxonomies.
	 */
	private static ModerationSurface surfaceOf(ContentReport.TargetType type) {
		if (type == null) {
			return null;
		}
		return switch (type) {
			case ISSUE -> ModerationSurface.ISSUE_DESCRIPTION;
			case COMMENT -> ModerationSurface.COMMENT;
			case ARTICLE -> ModerationSurface.ARTICLE_CONTENT;
			case ATTACHMENT -> ModerationSurface.ATTACHMENT;
			case USER -> ModerationSurface.PROFILE;
		};
	}

	/**
	 * The entity kind, in the lowercase form {@link ModerationRecord#getTargetType()}
	 * already uses — the client derives a route from it and must not have to know that
	 * two queues spell the same word differently.
	 */
	private static String targetTypeOf(ContentReport.TargetType type) {
		return type == null ? null : type.name().toLowerCase(Locale.ROOT);
	}

	private static String issueLink(Issue issue) {
		return "/issues/" + issue.getReadableId();
	}

	/** Deep link that opens a thread and scrolls to one comment — the app router's convention. */
	private static String commentLink(Issue issue, String commentId) {
		return issueLink(issue) + "?comment=" + commentId;
	}

	private static <T> Map<String, T> byId(Collection<T> found, Function<T, String> id) {
		Map<String, T> byId = new HashMap<>();
		found.forEach(item -> byId.put(id.apply(item), item));
		return byId;
	}

	private static Set<String> idsOf(List<ContentReport> rows,
			Function<ContentReport, String> id) {
		return distinct(rows.stream().map(id).toList());
	}

	private static Set<String> distinct(Collection<String> ids) {
		Set<String> wanted = new HashSet<>();
		for (String id : ids) {
			if (id != null && !id.isBlank()) {
				wanted.add(id);
			}
		}
		return wanted;
	}

	private static boolean typeIs(ContentReport report, ContentReport.TargetType type) {
		return report.getTargetType() == type;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String trimmed(String value) {
		return isBlank(value) ? null : value.trim();
	}
}
