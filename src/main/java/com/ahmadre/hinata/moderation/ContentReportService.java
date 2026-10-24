package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.block.BlockService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reporting content (HIN-26, HIN-170). Every report on the same content joins its one open case;
 * the reporter learns that it arrived and, later, what was decided, never anything about the case
 * or other reports. ({@code ReportService} is the analytics of the {@code report} package.)
 */
@Service
@RequiredArgsConstructor
public class ContentReportService {

	/** Reports one member may make in an hour, and in a day; urgent ones a day. */
	static final int PER_HOUR = 20;
	static final int PER_DAY = 100;
	static final int URGENT_PER_DAY = 5;

	/** What the list shows of a case. */
	static final int LIST_EXCERPT_MAX = 160;

	/** Categories whose report needs a reason (DSA Art. 16(2)(a)), and how long it must be at least. */
	static final Set<ReportCategory> EXPLAINED = EnumSet.of(ReportCategory.ILLEGAL, ReportCategory.OTHER,
			ReportCategory.CHILD_SAFETY);
	static final int EXPLANATION_MIN = 10;

	/** How many reported comments stay folded for the person who reported them, and for how long. */
	static final int FOLDED_MAX = 500;
	static final int FOLDED_MONTHS = 6;

	private final List<ContentSurface> surfaces;
	private final MongoTemplate mongo;
	private final ModerationStats stats;
	private final ModerationAlerts alerts;
	private final NotificationService notifications;
	private final BlockService blocks;
	private final Clock clock;

	/** What a member reports. A target of kind {@code OTHER} names a place instead of an id. */
	public record ReportRequest(@NotNull @Valid TargetRef target, @NotNull ReportCategory category,
			@Size(max = ContentReport.EXPLANATION_MAX_MEMBER) String explanation, @Size(max = 300) String location,
			boolean alsoBlock) {
	}

	/** The source a report counts as, and how it is filed. */
	record Source(ReportSource kind, String reporterKey, String reporterId, Notifier notifier) {

		static Source member(User reporter) {
			return new Source(ReportSource.MEMBER, reporter.getId(), reporter.getId(), null);
		}
	}

	/**
	 * Files [request] as [reporter]'s report: 404 for content they cannot see, 410 for content
	 * already removed, 400 for their own, 429 past their limits.
	 */
	public void submit(ReportRequest request, User reporter) {
		ReportCategory category = request.category();
		requireExplanation(category, request.explanation());
		Target target = resolve(request.target(), request.location(), reporter);
		if (target.snapshot() != null && (target.snapshot().contributorIds().contains(reporter.getId())
				|| target.ref().kind() == ContentKind.PERSON && reporter.getId().equals(target.ref().id()))) {
			throw ApiException.badRequest("error.moderation.ownContent");
		}
		String blockId = request.alsoBlock() ? blockable(target) : null;
		requireWithinLimits(reporter, category);

		Filed filed = file(target, category, request.explanation(), Source.member(reporter));
		if (blockId != null) {
			blocks.block(reporter, blockId);
		}
		notifications.noteInBell(List.of(reporter), "moderation.receipt.title", "moderation.receipt.body", null);
		alerts.reportFiled(filed, reporter.isModerator());
	}

	/**
	 * The comments [reporter] reported in the last six months that moderation has not removed,
	 * newest first and at most {@link #FOLDED_MAX} (HIN-173). They stay folded for the person who
	 * reported them, as Google Play asks of a report. Answered from {@code reporter_hidden} alone.
	 */
	public List<String> reportedCommentIds(User reporter) {
		Query query = Query.query(Criteria.where("reporterId").is(reporter.getId())
						.and("targetKind").is(ContentKind.COMMENT)
						.and("removed").is(false)
						.and("createdAt").gte(Retention.plusMonths(clock.instant(), -FOLDED_MONTHS)))
				.with(Sort.by(Sort.Direction.DESC, "createdAt"))
				.limit(FOLDED_MAX);
		query.fields().include("targetId").exclude("_id");
		return mongo.find(query, ContentReport.class).stream()
				.map(ContentReport::getTargetId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
	}

	/**
	 * Files what the text check found in [ref] as an automated report (HIN-176): [reporterKey] names
	 * the field, [terms] what was found. The same field finding again adds a hit to the report,
	 * never a second one. Read past the check of who may see it: the server just saved it, also
	 * from a mail nobody signed in to send. Gone content files nothing.
	 */
	public void fileAutomated(ContentRef ref, ReportCategory category, List<String> terms, String reporterKey) {
		ContentSurface surface = surfaces.stream().filter(candidate -> candidate.kind() == ref.kind())
				.findFirst().orElse(null);
		if (surface == null) {
			return;
		}
		ContentSnapshot snapshot = surface.forModeration(new TargetRef(ref.kind(), ref.id(), ref.parentId()))
				.orElse(null);
		if (snapshot == null || snapshot.removed()) {
			return;
		}
		Filed filed = file(new Target(snapshot.ref(), snapshot, locationOf(snapshot)), category,
				String.join(", ", terms), new Source(ReportSource.AUTOMATED, reporterKey, null, null));
		alerts.reportFiled(filed, false);
	}

	/** Content resolved for a report: where it lives and what it was; no snapshot for {@code OTHER}. */
	record Target(ContentRef ref, ContentSnapshot snapshot, String location) {
	}

	private Target resolve(TargetRef target, String location, User reporter) {
		if (target.kind() == ContentKind.OTHER) {
			if (location == null || location.isBlank()) {
				throw ApiException.badRequest("error.moderation.locationRequired");
			}
			return new Target(new ContentRef(ContentKind.OTHER, null, null, null), null, location.strip());
		}
		ContentSurface surface = surfaces.stream().filter(candidate -> candidate.kind() == target.kind())
				.findFirst().orElseThrow(() -> ApiException.badRequest("error.moderation.scopeNotAllowed"));
		ContentSnapshot snapshot = surface.snapshot(target, reporter)
				.orElseThrow(() -> ApiException.notFound(entityOf(target.kind())));
		if (snapshot.removed()) {
			throw ApiException.gone("error.moderation.removed");
		}
		return new Target(snapshot.ref(), snapshot, locationOf(snapshot));
	}

	private static String entityOf(ContentKind kind) {
		return switch (kind) {
			case ISSUE -> "issue";
			case COMMENT -> "comment";
			case ATTACHMENT -> "attachment";
			case ARTICLE -> "article";
			case PERSON, OTHER -> "user";
		};
	}

	/** {@code MESSE-14 · Standaufbau Halle 4}: where it is and what it belongs to. */
	static String locationOf(ContentSnapshot snapshot) {
		String where = snapshot.location() == null ? "" : snapshot.location();
		String context = snapshot.context() == null || snapshot.context().isBlank() ? null : snapshot.context();
		return ContentSnapshot.clip(context == null || context.equals(where) ? where : where + " · " + context, 300);
	}

	private static void requireExplanation(ReportCategory category, String explanation) {
		if (EXPLAINED.contains(category) && (explanation == null || explanation.strip().length() < EXPLANATION_MIN)) {
			throw ApiException.badRequest("error.moderation.explanationRequired", EXPLANATION_MIN);
		}
	}

	/** Who "also block" blocks: a comment's author, a reported person; nobody for anything else. */
	private static String blockable(Target target) {
		return switch (target.ref().kind()) {
			case COMMENT -> target.snapshot().lastEditorId();
			case PERSON -> target.ref().id();
			default -> throw ApiException.badRequest("error.moderation.blockNotApplicable");
		};
	}

	/**
	 * Counted in the database, not in one JVM, so two instances share the limit. A report that
	 * replaces an earlier one counts as the earlier one did.
	 */
	private void requireWithinLimits(User reporter, ReportCategory category) {
		Instant now = clock.instant();
		long lastHour = countSince(reporter, now.minus(Duration.ofHours(1)), null);
		if (lastHour >= PER_HOUR) {
			throw tooMany(Duration.ofHours(1));
		}
		long lastDay = countSince(reporter, now.minus(Duration.ofDays(1)), null);
		if (lastDay >= PER_DAY) {
			throw tooMany(Duration.ofDays(1));
		}
		if (urgent(category) && countSince(reporter, now.minus(Duration.ofDays(1)), Set.of(ReportCategory.THREAT,
				ReportCategory.CHILD_SAFETY)) >= URGENT_PER_DAY) {
			throw tooMany(Duration.ofDays(1));
		}
	}

	private long countSince(User reporter, Instant since, Set<ReportCategory> categories) {
		Criteria criteria = Criteria.where("reporterId").is(reporter.getId()).and("createdAt").gte(since);
		if (categories != null) {
			criteria = criteria.and("category").in(categories);
		}
		return mongo.count(Query.query(criteria), ContentReport.class);
	}

	private static ApiException tooMany(Duration window) {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.moderation.tooManyReports",
				Map.of("code", "moderation.tooManyReports", "retryAfter", String.valueOf(window.toSeconds())));
	}

	static boolean urgent(ReportCategory category) {
		return category == ReportCategory.THREAT || category == ReportCategory.CHILD_SAFETY;
	}

	/** What filing a report came to. */
	record Filed(ModerationCase moderationCase, boolean newCase, boolean becameUrgent, boolean newReport) {
	}

	/**
	 * Joins the open case of [target] or opens it, then files the report of [source] on it. Shared
	 * by members, reports without an account (HIN-175) and the text check (HIN-176).
	 */
	Filed file(Target target, ReportCategory category, String explanation, Source source) {
		Instant now = clock.instant();
		Opened opened = openOrJoin(target, category, source.kind(), now);
		ModerationCase moderationCase = opened.moderationCase();

		Query report = Query.query(Criteria.where("caseId").is(moderationCase.getId())
				.and("reporterKey").is(source.reporterKey()));
		Update reportUpdate = new Update()
				.set("category", category)
				.set("explanation", blankToNull(explanation))
				.set("updatedAt", now)
				.setOnInsert("source", source.kind())
				.setOnInsert("goodFaith", true)
				.setOnInsert("targetKind", target.ref().kind())
				.setOnInsert("targetId", target.ref().id())
				.setOnInsert("removed", false)
				.setOnInsert("createdAt", now);
		if (source.kind() == ReportSource.AUTOMATED) {
			// The text check finding the same again: one more hit, never one more report (HIN-176).
			reportUpdate.inc("hits", 1).set("lastSeenAt", now);
		}
		else {
			reportUpdate.setOnInsert("hits", 0);
		}
		if (source.reporterId() != null) {
			reportUpdate.setOnInsert("reporterId", source.reporterId());
		}
		if (source.notifier() != null) {
			reportUpdate.setOnInsert("notifier", source.notifier());
		}
		boolean newReport = upsertReport(report, reportUpdate);
		if (newReport) {
			mongo.updateFirst(Query.query(Criteria.where("_id").is(moderationCase.getId())),
					new Update().inc("reportCount", 1), ModerationCase.class);
			if (source.kind() == ReportSource.MEMBER || source.kind() == ReportSource.PUBLIC) {
				stats.notice(category);
			}
		}
		return new Filed(moderationCase, opened.inserted(), opened.becameUrgent(), newReport);
	}

	private boolean upsertReport(Query report, Update update) {
		try {
			return mongo.upsert(report, update, ContentReport.class).getUpsertedId() != null;
		}
		catch (DuplicateKeyException raced) {
			// The same source twice at once: the other made the row, this one is its correction.
			mongo.upsert(report, update, ContentReport.class);
			return false;
		}
	}

	private record Opened(ModerationCase moderationCase, boolean inserted, boolean becameUrgent) {
	}

	/**
	 * The open case of [target], made when there is none: one upsert over {@code open_target}, its
	 * filter repeating the index's partial filter. What was reported only ever grows; the snapshot
	 * and the list's excerpt are written by the first report alone.
	 */
	private Opened openOrJoin(Target target, ReportCategory category, ReportSource source, Instant now) {
		boolean urgent = urgent(category);
		if (target.ref().kind() == ContentKind.OTHER) {
			// Every report of something else is a case of its own, settled by hand.
			ModerationCase inserted = ModerationCase.builder()
					.target(target.ref()).location(target.location()).status(CaseStatus.OPEN).urgent(urgent)
					.categories(new LinkedHashSet<>(Set.of(category)))
					.sources(new LinkedHashSet<>(Set.of(source)))
					.listExcerpt(category.isSensitive() ? null : ContentSnapshot.clip(target.location(), LIST_EXCERPT_MAX))
					.firstReportedAt(now).lastReportedAt(now)
					.history(new ArrayList<>(List.of(new CaseEvent(now, CaseEvent.Kind.REPORTED, null, category.name()))))
					.createdAt(now).updatedAt(now).build();
			return new Opened(mongo.insert(inserted), true, false);
		}
		Update update = new Update()
				.addToSet("categories", category)
				.addToSet("sources", source)
				.max("lastReportedAt", now)
				.max("urgent", urgent)
				.set("updatedAt", now)
				.setOnInsert("target", target.ref())
				.setOnInsert("location", target.location())
				.setOnInsert("contributorIds", target.snapshot() == null ? Set.of() : target.snapshot().contributorIds())
				.setOnInsert("evidence", target.snapshot())
				.setOnInsert("firstReportedAt", now)
				.setOnInsert("history", List.of(new CaseEvent(now, CaseEvent.Kind.REPORTED, null, category.name())))
				.setOnInsert("pendingDeletes", List.of())
				.setOnInsert("rev", 0L)
				.setOnInsert("createdAt", now);
		// Internal content named by somebody without an account shows only the report until a
		// second person opens it (HIN-175): the form must not let a stranger have moderators read
		// whatever they point at. A member who reports it as well could see it anyway.
		switch (source) {
			case PUBLIC -> update.setOnInsert("evidenceWithheld", true);
			case MEMBER -> update.set("evidenceWithheld", false);
			default -> update.setOnInsert("evidenceWithheld", false);
		}
		if (category.isSensitive()) {
			// Nothing of sexual content or of children in the list, not even from an earlier report.
			update.unset("listExcerpt");
		}
		else if (source != ReportSource.PUBLIC) {
			// Withheld content shows no excerpt either.
			update.setOnInsert("listExcerpt", excerptOf(target));
		}
		String key = ModerationCase.keyOf(target.ref());
		// The equality of the filter is what an inserted case starts with: its key, and open.
		Query open = Query.query(Criteria.where("targetKey").is(key).and("status").is(CaseStatus.OPEN));
		FindAndModifyOptions before = FindAndModifyOptions.options().upsert(true).returnNew(false);
		ModerationCase previous;
		try {
			previous = mongo.findAndModify(open, update, before, ModerationCase.class);
		}
		catch (DuplicateKeyException raced) {
			// Two first reports at once: the other opened the case, this one joins it.
			previous = mongo.findAndModify(open, update, before, ModerationCase.class);
		}
		ModerationCase current = mongo.findOne(open, ModerationCase.class);
		if (current == null) {
			throw new IllegalStateException("open case for " + key + " vanished while reporting");
		}
		boolean becameUrgent = previous != null && !previous.isUrgent() && urgent;
		return new Opened(current, previous == null, becameUrgent);
	}

	private static String excerptOf(Target target) {
		ContentSnapshot snapshot = target.snapshot();
		String text = snapshot.excerpt() != null && !snapshot.excerpt().isBlank() ? snapshot.excerpt()
				: snapshot.title();
		return text == null ? null : ContentSnapshot.clip(text, LIST_EXCERPT_MAX);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
