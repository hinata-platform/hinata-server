package com.ahmadre.hinata.moderation.report;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes a user's report about a piece of content and gets it in front of a
 * moderator.
 *
 * <p>Four things have to be true of every report for the mechanism to be worth
 * having, and each one is a step below:
 *
 * <ol>
 *   <li><b>The reporter can actually see the content.</b> Reporting is authorized
 *       exactly like reading is — through {@link IssueService#getForUser} and the
 *       knowledge base's own visibility rule — because an unauthorized report is a
 *       probe: file one against a guessed id and the response tells you whether it
 *       exists.</li>
 *   <li><b>The same notice is not filed twice.</b> A queue where one determined
 *       reporter can bury everyone else's reports is a queue nobody works.</li>
 *   <li><b>Reporting is itself rate limited.</b> Mass-reporting a colleague is a
 *       recognised harassment pattern, and the report form is the one write in the
 *       product that is deliberately open to every authenticated user against
 *       content they do not own.</li>
 *   <li><b>The explanation is moderated.</b> {@link ContentReport#getNote()} is free
 *       text with a guaranteed admin reader — the last field that should bypass the
 *       gate every other user-authored string goes through.</li>
 * </ol>
 *
 * <p>What this class deliberately does <em>not</em> do is act on the report. No
 * content is hidden, no account is touched, nothing is auto-deleted on a threshold
 * of reports. A report is an assertion by one user, and a system that lets a handful
 * of them remove a colleague's work is a system whose moderation can be aimed. The
 * decision belongs to a human, which is also what DSA Art. 17 assumes when it
 * requires a statement of reasons someone can defend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentReportService {

	/**
	 * Hard ceiling on every free-text note a report carries — the reporter's
	 * explanation and, later, the moderator's reasoning. One constant rather than two
	 * because they end up in the same place: a queue row an admin reads and, for a
	 * removal, the statement of reasons built out of it.
	 */
	public static final int MAX_NOTE_LENGTH = 2000;

	/**
	 * Reports one account may file per hour.
	 *
	 * <p>Sized to be invisible to anyone acting in good faith — nobody encounters
	 * twenty distinct violations in an hour — while capping the damage of a script
	 * pointed at the endpoint. Deliberately generous rather than tight: a limit a real
	 * reporter can hit teaches them the button does not work, and the failure mode of
	 * moderation is always that people stop using it.
	 */
	public static final int REPORTS_PER_HOUR = 20;

	private final ContentReportRepository reports;
	private final IssueService issues;
	private final IssueCommentRepository comments;
	private final ArticleRepository articles;
	private final ProjectService projects;
	private final TeamService teams;
	private final UserRepository users;
	private final ModerationService moderation;
	private final NotificationService notifications;
	private final AuditService audit;

	/**
	 * Per-user token buckets, mirroring {@link com.ahmadre.hinata.config.RateLimitFilter}
	 * rather than introducing a second rate-limiting mechanism — same library, same
	 * greedy refill, same in-process map. Keyed by user id and not by IP on purpose:
	 * the abuse this guards against is one authenticated person aiming reports at a
	 * colleague, which no amount of IP budget catches, and unlike the filter's
	 * per-IP map the key space here is bounded by the workspace's own membership.
	 */
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/**
	 * Files [reason] against the given target on behalf of [reporter].
	 *
	 * @param contextId owning issue of an {@link ContentReport.TargetType#ATTACHMENT},
	 *                  ignored for every other type
	 * @throws ApiException 429 when the reporter is over their hourly budget, 409 when
	 *                      they already have an open report on this target, 403/404
	 *                      when the target is not theirs to see
	 * @throws com.ahmadre.hinata.moderation.ModerationException when the note itself
	 *                      breaks the content rules
	 */
	public ContentReport file(User reporter, ContentReport.TargetType targetType, String targetId,
			String contextId, ContentReport.ReportReason reason, String note) {
		consumeBudget(reporter.getId());
		Target target = resolve(targetType, targetId, contextId, reporter);
		if (reports.existsByReporterIdAndTargetTypeAndTargetIdAndState(reporter.getId(), targetType,
				target.id(), ContentReport.State.OPEN)) {
			throw ApiException.conflict("error.report.duplicate");
		}
		String explanation = (note == null || note.isBlank()) ? null : note.trim();
		if (explanation != null) {
			// COMMENT rather than a surface of its own: the note is prose written by a
			// colleague in a hurry, so it earns the same technical-vocabulary relief a
			// comment gets — "he keeps telling people to kill the build" must not be
			// refused as a threat.
			moderation.checkText(explanation, ModerationSurface.COMMENT);
		}
		ContentReport saved = reports.save(ContentReport.builder()
				.reporterId(reporter.getId())
				.targetType(targetType)
				.targetId(target.id())
				.contextId(target.contextId())
				.projectId(target.projectId())
				.reason(reason)
				.note(explanation)
				.build());
		audit.event(AuditAction.CONTENT_REPORTED)
				.actor(reporter)
				.target(target.id(), target.label())
				.meta("targetType", targetType.name())
				.meta("reason", reason.name())
				.log();
		notifyModerators(saved, reporter, target);
		return saved;
	}

	/**
	 * Charges one token against the reporter's hourly budget.
	 *
	 * @throws ApiException 429 when the budget is exhausted
	 */
	private void consumeBudget(String userId) {
		Bucket bucket = buckets.computeIfAbsent(userId, key -> Bucket.builder()
				.addLimit(Bandwidth.builder()
						.capacity(REPORTS_PER_HOUR)
						.refillGreedy(REPORTS_PER_HOUR, Duration.ofHours(1))
						.build())
				.build());
		if (!bucket.tryConsume(1)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.report.tooMany");
		}
	}

	/**
	 * One page of the moderator queue, newest first. A {@code null} [state] widens it
	 * to every report rather than only the backlog — the question "did we already
	 * decide this, and what did we say?" arrives months later, usually from the person
	 * who was reported, and a queue that only shows what is undecided cannot answer it.
	 *
	 * @param pageable page and size only — the ordering belongs to this method, because
	 *                 a queue read in any order other than newest-first is a queue whose
	 *                 top row is not the thing waiting longest for an answer
	 */
	public Page<ContentReport> list(ContentReport.State state, Pageable pageable) {
		return state == null
				? reports.findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
						Sort.by(Sort.Direction.DESC, "createdAt")))
				: reports.findByStateOrderByCreatedAtDesc(state, pageable);
	}

	/** Size of the open backlog — the badge on the admin section, and what an alert watches. */
	public long countOpen() {
		return reports.countByState(ContentReport.State.OPEN);
	}

	/**
	 * Closes report [id] with a moderator's decision.
	 *
	 * <p>Closing is deliberately not one-way: a report may be decided again, and the
	 * row keeps only the latest decision while the audit log keeps every one of them.
	 * DSA Art. 20 requires an internal complaint mechanism that can actually reverse a
	 * decision, and a queue whose rows are frozen once touched forces that reversal to
	 * happen somewhere no one can see it.
	 *
	 * <p>The handler's note is <em>not</em> put through the moderation gate, unlike the
	 * reporter's. It is written by the admin whose job is to judge the gate's output;
	 * refusing their reasoning as abusive would jam the very queue that resolves
	 * abusive content, and the wording of a removal notice routinely has to quote what
	 * made the content unacceptable.
	 *
	 * @throws ApiException 400 when [state] would leave the report open, 404 when it
	 *                      no longer exists
	 */
	public ContentReport handle(User moderator, String id, ContentReport.State state, String note) {
		if (state == null || state == ContentReport.State.OPEN) {
			throw ApiException.badRequest("error.moderation.decisionRequired");
		}
		ContentReport report = reports.findById(id)
				.orElseThrow(() -> ApiException.notFound("contentReport"));
		report.setState(state);
		report.setHandledBy(moderator.getId());
		report.setHandledAt(Instant.now());
		report.setHandlerNote(trimNote(note));
		ContentReport saved = reports.save(report);
		audit.event(AuditAction.CONTENT_REPORT_HANDLED)
				.actor(moderator)
				.target(saved.getId(), saved.getTargetId())
				.meta("state", state.name())
				.meta("targetType", saved.getTargetType() == null ? null : saved.getTargetType().name())
				.meta("reason", saved.getReason() == null ? null : saved.getReason().name())
				.log();
		return saved;
	}

	/**
	 * Normalises a free-text note: blank becomes {@code null}, and anything over the
	 * cap is cut. The cut is here as well as in the request DTO because this is a
	 * service entry point, and a validated controller says nothing about the next
	 * caller.
	 *
	 * <p>Public because the record queue stores the same kind of note against a
	 * moderation record, and a second copy of this rule is a second cap that drifts.
	 */
	public static String trimNote(String note) {
		if (note == null || note.isBlank()) {
			return null;
		}
		String trimmed = note.trim();
		return trimmed.length() > MAX_NOTE_LENGTH ? trimmed.substring(0, MAX_NOTE_LENGTH) : trimmed;
	}

	/**
	 * The reported thing, once it has been found and the reporter's access to it
	 * proven.
	 *
	 * @param id        canonical id stored on the report — the resolved entity id, not
	 *                  whatever alias the client sent (an issue may be addressed by a
	 *                  readable key, and two reports about one issue must collapse onto
	 *                  the same target)
	 * @param contextId owning issue for an attachment, otherwise {@code null}
	 * @param projectId owning project when the target has one
	 * @param label     short human-readable handle for the queue row and the audit
	 *                  entry — an issue key, a file name, a display name; never the
	 *                  reported content itself
	 */
	private record Target(String id, String contextId, String projectId, String label) {
	}

	private Target resolve(ContentReport.TargetType targetType, String targetId, String contextId,
			User reporter) {
		if (targetId == null || targetId.isBlank()) {
			throw ApiException.badRequest("error.report.targetMissing");
		}
		return switch (targetType) {
			case ISSUE -> {
				Issue issue = issues.getForUser(targetId, reporter);
				yield new Target(issue.getId(), null, issue.getProjectId(), issue.getReadableId());
			}
			case COMMENT -> {
				IssueComment comment = comments.findById(targetId)
						.orElseThrow(() -> ApiException.notFound("comment"));
				// The comment carries its issue, and the issue carries the project whose
				// membership decides — so a comment is authorized exactly like reading
				// the thread it lives in.
				Issue issue = issues.getForUser(comment.getIssueId(), reporter);
				yield new Target(comment.getId(), issue.getId(), issue.getProjectId(),
						issue.getReadableId());
			}
			case ARTICLE -> {
				Article article = articles.findById(targetId)
						.orElseThrow(() -> ApiException.notFound("article"));
				if (!canSee(article, reporter)) {
					// Mirrors ArticleController: a 403 here would confirm the article
					// exists to someone who may not know that.
					throw ApiException.notFound("article");
				}
				yield new Target(article.getId(), null, article.getProjectId(), article.getTitle());
			}
			case ATTACHMENT -> {
				if (contextId == null || contextId.isBlank()) {
					// Attachments are embedded in their issue and have no addressable
					// existence without it — the client already reaches them through
					// /issues/{issueId}/attachments/{id} and knows both halves.
					throw ApiException.badRequest("error.report.contextMissing");
				}
				Issue issue = issues.getForUser(contextId, reporter);
				Issue.Attachment attachment = issue.getAttachments().stream()
						.filter(a -> targetId.equals(a.getId()))
						.findFirst()
						.orElseThrow(() -> ApiException.notFound("attachment"));
				yield new Target(attachment.getId(), issue.getId(), issue.getProjectId(),
						attachment.getFileName());
			}
			case USER -> {
				if (targetId.equals(reporter.getId())) {
					throw ApiException.badRequest("error.report.self");
				}
				User reported = users.findById(targetId)
						.orElseThrow(() -> ApiException.notFound("user"));
				// No project scope and no access check: reporting a person is about a
				// pattern of behaviour that no single item carries, and requiring the
				// reporter to first name a piece of content is exactly the gap Google
				// Play rejects apps for.
				yield new Target(reported.getId(), null, null, reported.getDisplayName());
			}
		};
	}

	/**
	 * The knowledge base's visibility rule, applied to a reporter. Kept in step with
	 * {@code ArticleController}: project-scoped articles need access to that project,
	 * team-scoped ones membership of that team, and global ones nothing at all.
	 */
	private boolean canSee(Article article, User user) {
		if (user.isAdmin()) {
			return true;
		}
		if (article.getProjectId() != null) {
			return projects.visibleTo(user).stream()
					.map(Project::getId)
					.anyMatch(id -> id.equals(article.getProjectId()));
		}
		if (article.getTeamId() != null) {
			return teams.visibleTo(user).stream()
					.map(Team::getId)
					.anyMatch(id -> id.equals(article.getTeamId()));
		}
		return true;
	}

	/**
	 * Tells the admins a report is waiting.
	 *
	 * <p>Every active admin is notified, because a report that only reaches a queue
	 * nobody opens is not notice-and-action — DSA Art. 16(6) expects a timely decision,
	 * and "timely" starts when somebody learns about it. Failing to notify never fails
	 * the report itself: the row is already saved and the queue is the source of truth,
	 * so a mail outage must not cost the reporter their submission.
	 */
	private void notifyModerators(ContentReport report, User reporter, Target target) {
		try {
			List<User> admins = users.findByRolesContainingAndActiveIsTrue(Role.ADMIN);
			if (admins.isEmpty()) {
				log.warn("Content report {} has no active admin to notify", report.getId());
				return;
			}
			notifications.notifyAdminsContentReported(admins, reporter, target.label(),
					report.getReason().urgent(), "/admin/moderation?report=" + report.getId());
		}
		catch (RuntimeException ex) {
			log.warn("Failed to notify moderators about report {}: {}", report.getId(), ex.toString());
		}
	}
}
