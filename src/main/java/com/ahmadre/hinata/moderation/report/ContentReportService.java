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
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.moderation.escalation.ModerationEscalation;
import com.ahmadre.hinata.moderation.freeze.FrozenContent;
import com.ahmadre.hinata.moderation.freeze.FrozenContentService;
import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
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
import java.util.Locale;
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

	/**
	 * Reports one account may file <em>that cause a freeze</em> per day.
	 *
	 * <p>Far tighter than {@link #REPORTS_PER_HOUR}, and for a different reason.
	 * That budget caps ordinary reporting, which is cheap to be wrong about — a
	 * spurious report costs a moderator ten seconds. A freeze costs the author their
	 * content, immediately, on one unverified assertion, reversible only by an
	 * audited admin action. This class's own javadoc warns that "a system that lets a
	 * handful of them remove a colleague's work is a system whose moderation can be
	 * aimed", and freezing on a single report is exactly that, only stronger.
	 *
	 * <p>Freezing anyway is still the right trade — leaving suspected child sexual
	 * content up while an admin wakes is the worse outcome — so the answer to the
	 * abuse risk is not to freeze rarely but to freeze <em>attributably</em> and
	 * release fast: every freeze carries its reporter, and over this budget the
	 * report is still <b>filed and queued in full</b>, it simply does not freeze.
	 * Nobody's notice is ever discarded.
	 */
	public static final int FREEZING_REPORTS_PER_DAY = 3;

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
	private final FrozenContentService frozen;
	private final List<ModerationEscalation> escalations;

	/**
	 * Per-user token buckets, mirroring {@link com.ahmadre.hinata.config.RateLimitFilter}
	 * rather than introducing a second rate-limiting mechanism — same library, same
	 * greedy refill, same in-process map. Keyed by user id and not by IP on purpose:
	 * the abuse this guards against is one authenticated person aiming reports at a
	 * colleague, which no amount of IP budget catches, and unlike the filter's
	 * per-IP map the key space here is bounded by the workspace's own membership.
	 */
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/** The freeze-causing budget, kept separately so one cannot exhaust the other. */
	private final Map<String, Bucket> freezeBuckets = new ConcurrentHashMap<>();

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
		// Freeze BEFORE the report row, and outside any transaction. Both halves
		// matter. The ordering: freezing and then failing to file leaves content
		// unreachable with no report — safe, and visible in the audit log. Filing and
		// then failing to freeze leaves suspected child sexual content up, which is
		// the outcome this exists to prevent. And no transaction, because the gate
		// two lines above is a gate: MongoModerationRecorder's javadoc spells out
		// that a gate inside a transaction "would roll back the very row that
		// explains why it rolled back", and SprintService.start was fixed for exactly
		// that. The order is what makes the sequence safe; a transaction would only
		// make it fail differently.
		FrozenContent freeze = freezeIfWarranted(reporter, targetType, target, reason);
		ContentReport saved = reports.save(ContentReport.builder()
				.reporterId(reporter.getId())
				.targetType(targetType)
				.targetId(target.id())
				.contextId(target.contextId())
				.projectId(target.projectId())
				.reason(reason)
				.note(explanation)
				.build());
		frozen.attachReport(freeze, saved.getId());
		audit.event(AuditAction.CONTENT_REPORTED)
				.actor(reporter)
				.target(target.id(), target.label())
				.meta("targetType", targetType.name())
				.meta("reason", reason.name())
				.meta("frozen", String.valueOf(freeze != null))
				.log();
		escalate(saved, targetType, target);
		notifyModerators(saved, reporter, target, freeze != null);
		return saved;
	}

	/**
	 * Freezes the reported target when the reason calls for it.
	 *
	 * <p><b>{@code SEXUAL_MINORS} only, not {@link ContentReport.ReportReason#urgent()}.</b>
	 * That predicate also covers {@code MALWARE}, and freezing on a malware report
	 * would be a weapon for no safety gain: a malicious file is refused at upload
	 * and never persisted, and {@link ModerationCategory#MALWARE} is documented as a
	 * scanner verdict that "is never appealable on the merits and never routed to a
	 * content moderator". There is nothing for a freeze to preserve and nothing for a
	 * human to decide — only an attachment anyone could make disappear by naming the
	 * right reason.
	 *
	 * <p>Never fails the report. A registry that cannot be written is a reason to
	 * escalate and to log, not to discard somebody's notice — the report row and the
	 * queue are the fallback the product had before freeze existed.
	 *
	 * @return the freeze, or {@code null} when none was raised
	 */
	private FrozenContent freezeIfWarranted(User reporter, ContentReport.TargetType targetType,
			Target target, ContentReport.ReportReason reason) {
		if (reason == null || reason.category() != ModerationCategory.SEXUAL_MINORS) {
			return null;
		}
		FrozenTargetType type = freezableType(targetType);
		if (type == null) {
			return null;
		}
		if (!freezeBudget(reporter.getId())) {
			log.warn("Report by {} would have frozen {} {} but the account is over its daily "
					+ "freeze budget — the report is filed and queued, unfrozen",
					reporter.getId(), targetType, target.id());
			return null;
		}
		try {
			return frozen.freeze(new FrozenContentService.Request(type, target.id(),
					target.contextId(), target.objectKeys(), ModerationCategory.SEXUAL_MINORS,
					null, reporter.getId(), null, "report:" + reason.name()));
		}
		catch (RuntimeException ex) {
			log.error("Could not freeze reported {} {}: {}", targetType, target.id(), ex.toString());
			return null;
		}
	}

	/**
	 * The freeze target type a report target maps to, or {@code null} for one that
	 * cannot be frozen.
	 *
	 * <p>{@link ContentReport.TargetType#USER} is the {@code null}: a report about a
	 * person is about a pattern of behaviour with no content id attached — that is
	 * why it exists as its own kind — and freezing an account is a suspension, which
	 * is a different action with a different due-process story. Nothing in WP-3
	 * grants it, so it is not quietly granted here.
	 */
	private static FrozenTargetType freezableType(ContentReport.TargetType targetType) {
		return switch (targetType) {
			case ISSUE -> FrozenTargetType.ISSUE;
			case COMMENT -> FrozenTargetType.COMMENT;
			case ARTICLE -> FrozenTargetType.ARTICLE;
			case ATTACHMENT -> FrozenTargetType.ATTACHMENT;
			case USER -> null;
		};
	}

	/** Charges one token against the reporter's daily freeze-causing budget. */
	private boolean freezeBudget(String userId) {
		return freezeBuckets.computeIfAbsent(userId, key -> Bucket.builder()
						.addLimit(Bandwidth.builder()
								.capacity(FREEZING_REPORTS_PER_DAY)
								.refillGreedy(FREEZING_REPORTS_PER_DAY, Duration.ofDays(1))
								.build())
						.build())
				.tryConsume(1);
	}

	/**
	 * Hands an urgent report to the operator's escalation targets.
	 *
	 * <p>{@link ContentReport.ReportReason#urgent()} here, unlike the freeze trigger
	 * above — and the asymmetry is deliberate. Escalating is telling a human that
	 * something needs looking at, which is exactly right for a malware report even
	 * though there is nothing to freeze. Removing someone's content is not.
	 *
	 * <p>The payload names the report and the target, and carries neither the
	 * reporter's note nor the target's label: the label is an article title or a file
	 * name, which for this category is potentially the violating material itself.
	 */
	private void escalate(ContentReport report, ContentReport.TargetType targetType, Target target) {
		if (report.getReason() == null || !report.getReason().urgent() || escalations.isEmpty()) {
			return;
		}
		ModerationEscalation.Event event = new ModerationEscalation.Event(report.getId(),
				report.getReason().category(), surfaceOf(targetType),
				targetType.name().toLowerCase(Locale.ROOT) + ":" + target.id(), Instant.now());
		for (ModerationEscalation destination : escalations) {
			try {
				destination.escalate(event);
			}
			catch (RuntimeException ex) {
				log.error("Escalation target {} failed for report {}: {}", destination.id(),
						report.getId(), ex.toString());
			}
		}
	}

	/**
	 * Every stored object an issue owns.
	 *
	 * <p>Freezing an issue without these leaves its attachments downloadable to
	 * anyone holding a presigned URL or the download route, which is the whole of
	 * what an attachment is: bytes with an addressable existence independent of the
	 * row that lists them.
	 */
	private static List<String> attachmentKeys(Issue issue) {
		if (issue.getAttachments() == null) {
			return List.of();
		}
		return issue.getAttachments().stream()
				.map(Issue.Attachment::getObjectKey)
				.filter(key -> key != null && !key.isBlank())
				.toList();
	}

	/** The reported target expressed as a moderation surface, so both queues speak one vocabulary. */
	private static ModerationSurface surfaceOf(ContentReport.TargetType type) {
		return switch (type) {
			case ISSUE -> ModerationSurface.ISSUE_DESCRIPTION;
			case COMMENT -> ModerationSurface.COMMENT;
			case ARTICLE -> ModerationSurface.ARTICLE_CONTENT;
			case ATTACHMENT -> ModerationSurface.ATTACHMENT;
			case USER -> ModerationSurface.PROFILE;
		};
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
	 * @param objectKeys every stored object the target owns — a voice blob, an
	 *                  attachment's bytes, an issue's attachments. Resolved here
	 *                  because this is where the entity is already in hand, and a
	 *                  freeze that reaches the row but not the bytes leaves the
	 *                  material downloadable by anyone who kept the URL
	 */
	private record Target(String id, String contextId, String projectId, String label,
			List<String> objectKeys) {
	}

	private Target resolve(ContentReport.TargetType targetType, String targetId, String contextId,
			User reporter) {
		if (targetId == null || targetId.isBlank()) {
			throw ApiException.badRequest("error.report.targetMissing");
		}
		return switch (targetType) {
			case ISSUE -> {
				Issue issue = issues.getForUser(targetId, reporter);
				yield new Target(issue.getId(), null, issue.getProjectId(), issue.getReadableId(),
						attachmentKeys(issue));
			}
			case COMMENT -> {
				IssueComment comment = comments.findById(targetId)
						.orElseThrow(() -> ApiException.notFound("comment"));
				// The comment carries its issue, and the issue carries the project whose
				// membership decides — so a comment is authorized exactly like reading
				// the thread it lives in.
				Issue issue = issues.getForUser(comment.getIssueId(), reporter);
				yield new Target(comment.getId(), issue.getId(), issue.getProjectId(),
						issue.getReadableId(),
						comment.getVoice() == null ? List.of()
								: List.of(comment.getVoice().getObjectKey()));
			}
			case ARTICLE -> {
				Article article = articles.findById(targetId)
						.orElseThrow(() -> ApiException.notFound("article"));
				if (!canSee(article, reporter)) {
					// Mirrors ArticleController: a 403 here would confirm the article
					// exists to someone who may not know that.
					throw ApiException.notFound("article");
				}
				yield new Target(article.getId(), null, article.getProjectId(), article.getTitle(),
						List.of());
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
						attachment.getFileName(), List.of(attachment.getObjectKey()));
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
				yield new Target(reported.getId(), null, null, reported.getDisplayName(), List.of());
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
	private void notifyModerators(ContentReport report, User reporter, Target target,
			boolean frozenTarget) {
		try {
			List<User> admins = users.findByRolesContainingAndActiveIsTrue(Role.ADMIN);
			if (admins.isEmpty()) {
				log.warn("Content report {} has no active admin to notify", report.getId());
				return;
			}
			// A frozen target's label is suppressed. The label is an article title or a
			// file name, and for this category the title may itself be the violating
			// material — while this notice becomes a persisted row, an SMTP mail and a
			// push body on every admin's lock screen. Freezing the content and then
			// mailing its title to everyone would leave the one copy that cannot be
			// recalled in the one place with the widest audience.
			notifications.notifyAdminsContentReported(admins, reporter,
					frozenTarget ? null : target.label(),
					report.getReason().urgent(), "/admin/moderation?report=" + report.getId());
		}
		catch (RuntimeException ex) {
			log.warn("Failed to notify moderators about report {}: {}", report.getId(), ex.toString());
		}
	}
}
