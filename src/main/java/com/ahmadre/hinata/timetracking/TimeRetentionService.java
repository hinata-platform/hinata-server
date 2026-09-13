package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The operator's storage limitation, applied (Art. 5 Abs. 1 lit. e DSGVO).
 *
 * <p>Two rules, and the admin area states them as the specification:
 *
 * <ul>
 * <li>{@code descriptionPurgeMonths} empties the free text on the entries of
 * <em>deleted</em> accounts, once the entry's day is that many months back, and the
 * notes on their timesheets once the period ended that long ago. The hours and the
 * decisions stay — they are the project's record — and only the sentences that still
 * describe a person who is gone go.</li>
 * <li>{@code entryPurgeMonths} deletes entries whose day is that many months back,
 * for everyone — except entries inside a period that is submitted or approved.
 * Those are the substance of a signed-off record, and deleting the hours under an
 * approval would leave a payroll decision that no longer adds up. Never less than
 * 24 months ({@code TimePolicy.ENTRY_RETENTION_MIN_MONTHS}).</li>
 * </ul>
 *
 * <p>{@code 0} for both is the default and means nothing is ever deleted: entries
 * are the project's record, and an instance must not start destroying data because
 * it was upgraded. Choosing a period is the operator's deliberate act.
 *
 * <p>Built for a large instance and for two of them:
 *
 * <ul>
 * <li>Claimed per night by an insert ({@link TimeRetentionRun}), so a second
 * instance walks away.</li>
 * <li>A time budget per run. The entry sweep keeps a cursor, so a night that runs
 * out of time hands the next night the day and the entry it stopped at, instead of
 * starting over at the oldest day — which, with years of approved periods kept, would
 * be a sweep that never reaches anything it may delete.</li>
 * <li>The frozen periods are read once per run and held as intervals, not queried
 * per batch.</li>
 * <li>Descriptions are found through a partial index that only holds entries that
 * still have one, so a cleared entry is never read again.</li>
 * </ul>
 *
 * <p>Every run that was claimed is recorded — counters written as it goes, an audit
 * record at the end even when it failed. The record that records were deleted must
 * outlive them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeRetentionService {

	static final int BATCH = 500;

	/** How long one night's run may take before it yields to the next night. */
	static final Duration TIME_BUDGET = Duration.ofMinutes(5);

	/** The one-off marker for having recorded the accounts deleted before HIN-89. */
	static final String DEPARTED_BACKFILL = "departed-users-backfill";

	/** Where the entry sweep stopped, for the next night. */
	static final String ENTRY_CURSOR = "entry-cursor";

	private final TimeTrackingSettings policy;
	private final DepartedTimeUserRepository departed;
	private final AuditService audit;
	private final MongoTemplate mongo;
	private final Clock clock;

	/** One entry as the purge needs it. */
	private record Row(ObjectId id, String userId, String projectId, String issueId, int minutes) {
	}

	/**
	 * Runs tonight's sweep, or nothing: when the module is off, when neither period
	 * is configured, or when another instance has already claimed the night.
	 */
	public Optional<TimeRetentionRun> run() {
		if (!policy.advancedEnabled()) {
			return Optional.empty();
		}
		TimeTrackingSettings.Retention retention = policy.retention();
		if (retention.descriptionPurgeMonths() <= 0 && retention.entryPurgeMonths() <= 0) {
			return Optional.empty();
		}
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		TimeRetentionRun run = TimeRetentionRun.builder().id(today.toString())
				.startedAt(clock.instant()).build();
		try {
			mongo.insert(run);
		}
		catch (DuplicateKeyException claimedElsewhere) {
			return Optional.empty();
		}
		Instant deadline = clock.instant().plus(TIME_BUDGET);
		boolean complete = false;
		String failure = null;
		try {
			boolean descriptions = true;
			if (retention.descriptionPurgeMonths() > 0) {
				recordAccountsDeletedBeforeThisStage();
				descriptions = clearDescriptions(today.minusMonths(retention.descriptionPurgeMonths()),
						deadline, run);
			}
			boolean entries = retention.entryPurgeMonths() <= 0
					|| purgeEntries(today.minusMonths(retention.entryPurgeMonths()), deadline, run);
			complete = descriptions && entries;
		}
		catch (RuntimeException ex) {
			failure = ex.getClass().getSimpleName();
			throw ex;
		}
		finally {
			run.setComplete(complete);
			run.setFinishedAt(clock.instant());
			progress(run);
			audit.event(AuditAction.TIME_RETENTION_RUN)
					.target(run.getId(), run.getId())
					.meta("descriptionPurgeMonths", String.valueOf(retention.descriptionPurgeMonths()))
					.meta("entryPurgeMonths", String.valueOf(retention.entryPurgeMonths()))
					.meta("descriptionsCleared", String.valueOf(run.getDescriptionsCleared()))
					.meta("approvalNotesCleared", String.valueOf(run.getApprovalNotesCleared()))
					.meta("entriesDeleted", String.valueOf(run.getEntriesDeleted()))
					.meta("entriesKept", String.valueOf(run.getEntriesKept()))
					.meta("complete", String.valueOf(complete))
					.meta("failure", failure)
					.log();
		}
		return Optional.of(run);
	}

	// --- descriptions of departed accounts --------------------------------------

	/**
	 * Empties the descriptions of departed accounts' entries dated before {@code before}.
	 *
	 * <p>{@code description $gt ""} is what lets {@code user_date_described} answer:
	 * the partial index holds only entries with a description, and unsetting one takes
	 * the entry out of the index — so each person costs one index probe once their old
	 * entries are clear, however many they have.
	 */
	private boolean clearDescriptions(LocalDate before, Instant deadline, TimeRetentionRun run) {
		for (DepartedTimeUser person : departed.findAll(Sort.by("_id"))) {
			while (true) {
				if (!clock.instant().isBefore(deadline)) {
					return false;
				}
				Query batch = Query.query(Criteria.where("userId").is(person.getId())
						.and("date").lt(before)
						.and("description").gt("")).limit(BATCH);
				batch.fields().include("_id");
				List<Object> ids = raw(batch).stream().map(document -> document.get("_id")).toList();
				if (ids.isEmpty()) {
					break;
				}
				long cleared = mongo.updateMulti(Query.query(Criteria.where("_id").in(ids)),
						new Update().unset("description"), WorkItem.class).getModifiedCount();
				run.setDescriptionsCleared(run.getDescriptionsCleared() + cleared);
				progress(run);
				if (ids.size() < BATCH) {
					break;
				}
			}
			run.setApprovalNotesCleared(run.getApprovalNotesCleared()
					+ clearApprovalNotes(person.getId(), before));
			progress(run);
		}
		return true;
	}

	/**
	 * Empties the notes on a departed account's timesheets whose period ended before
	 * {@code before}: the reasons given when one was handed in, sent back or signed off.
	 * The same kind of sentence as a description, about the same person, kept for the
	 * same time. The decisions stay, with who made them and when.
	 *
	 * <p>Two updates rather than one, because {@code history.$[]} refuses a document
	 * without a history, and a note on the timesheet itself needs none. Each bumps the
	 * version the service's own writes are checked against, so a decision saved from a
	 * copy read before the sweep cannot put the notes back.
	 */
	private long clearApprovalNotes(String userId, LocalDate before) {
		long onSheets = mongo.updateMulti(Query.query(Criteria.where("userId").is(userId)
						.and("periodEnd").lt(before).and("note").gt("")),
				new Update().unset("note").inc("version", 1), TimesheetApproval.class).getModifiedCount();
		long inHistory = mongo.updateMulti(Query.query(Criteria.where("userId").is(userId)
						.and("periodEnd").lt(before).and("history.note").gt("")),
				new Update().unset("history.$[].note").inc("version", 1), TimesheetApproval.class)
				.getModifiedCount();
		return onSheets + inHistory;
	}

	/**
	 * Writes down the accounts that were deleted before the module recorded deletions
	 * itself — once, behind a marker, as its own step of the run.
	 *
	 * <p>The one read of the whole collection this job ever makes: the distinct user
	 * ids on entries (a scan of {@code user_date}), checked against the user collection
	 * in chunks. {@code deletedAt} stays empty for these: when they were deleted is not
	 * known, and the day of this run would be a date nobody may later count from.
	 */
	private void recordAccountsDeletedBeforeThisStage() {
		if (mongo.exists(Query.query(Criteria.where("_id").is(DEPARTED_BACKFILL)),
				TimeRetentionRun.class)) {
			return;
		}
		List<String> ids = mongo.findDistinct(new Query(), "userId", WorkItem.class, String.class)
				.stream().filter(Objects::nonNull).toList();
		for (int start = 0; start < ids.size(); start += BATCH) {
			List<String> chunk = ids.subList(start, Math.min(ids.size(), start + BATCH));
			Query existing = Query.query(Criteria.where("_id").in(chunk));
			existing.fields().include("_id");
			Set<String> alive = new HashSet<>();
			mongo.query(User.class).as(Document.class).matching(existing).all()
					.forEach(user -> alive.add(WorkItemDocuments.id(user)));
			for (String id : chunk) {
				if (!alive.contains(id) && !departed.existsById(id)) {
					departed.save(new DepartedTimeUser(id, null));
				}
			}
		}
		Instant now = clock.instant();
		try {
			mongo.insert(TimeRetentionRun.builder().id(DEPARTED_BACKFILL).startedAt(now)
					.finishedAt(now).complete(true).build());
		}
		catch (DuplicateKeyException recordedMeanwhile) {
			// Harmless: the saves above are idempotent by id.
		}
	}

	// --- entries past the retention period -------------------------------------

	/**
	 * Deletes entries dated before {@code before}, a day at a time and a batch at a
	 * time within the day, continuing where the last night stopped.
	 *
	 * <p>By day because that is what {@code date_id} walks: the oldest day is an index
	 * seek, and within one day the batches go by {@code _id}. A cycle ends when no day
	 * before the cutoff is left; the cursor is then dropped and the next cycle starts
	 * at the oldest day again, which is how a period reopened since becomes deletable.
	 */
	private boolean purgeEntries(LocalDate before, Instant deadline, TimeRetentionRun run) {
		FrozenPeriods frozen = FrozenPeriods.load(mongo, before);
		TimeRetentionRun cursor = mongo.findById(ENTRY_CURSOR, TimeRetentionRun.class);
		LocalDate day;
		ObjectId after;
		if (cursor != null && cursor.getCursorDay() != null && cursor.getCursorDay().isBefore(before)) {
			day = cursor.getCursorDay();
			after = cursor.getCursorId() == null ? null : new ObjectId(cursor.getCursorId());
		}
		else {
			day = nextDay(null, before);
			after = null;
		}
		while (day != null) {
			while (true) {
				if (!clock.instant().isBefore(deadline)) {
					saveCursor(day, after);
					return false;
				}
				Criteria criteria = Criteria.where("date").is(day);
				if (after != null) {
					criteria = criteria.and("_id").gt(after);
				}
				Query batch = Query.query(criteria).with(Sort.by("_id")).limit(BATCH);
				batch.fields().include("userId", "projectId", "issueId", "durationMinutes");
				List<Row> rows = raw(batch).stream()
						.map(document -> new Row(document.getObjectId("_id"),
								document.getString("userId"), document.getString("projectId"),
								document.getString("issueId"), WorkItemDocuments.minutes(document)))
						.toList();
				if (rows.isEmpty()) {
					break;
				}
				after = rows.getLast().id();
				List<Row> doomed = new ArrayList<>(rows.size());
				for (Row row : rows) {
					if (frozen.holds(row.userId(), row.projectId(), day)) {
						run.setEntriesKept(run.getEntriesKept() + 1);
					}
					else {
						doomed.add(row);
					}
				}
				delete(doomed, run);
				saveCursor(day, after);
				progress(run);
				if (rows.size() < BATCH) {
					break;
				}
			}
			day = nextDay(day, before);
			after = null;
		}
		mongo.remove(Query.query(Criteria.where("_id").is(ENTRY_CURSOR)), TimeRetentionRun.class);
		return true;
	}

	/** The oldest day after {@code after} (or at all) that still has entries before the cutoff. */
	private LocalDate nextDay(LocalDate after, LocalDate before) {
		Criteria criteria = after == null
				? Criteria.where("date").lt(before)
				: Criteria.where("date").gt(after).lt(before);
		Query query = Query.query(criteria).with(Sort.by("date")).limit(1);
		query.fields().include("date");
		List<Document> first = raw(query);
		return first.isEmpty() ? null : WorkItemDocuments.day(first.getFirst(), "date");
	}

	/**
	 * Deletes the entries and takes their minutes off the issues they were booked on.
	 *
	 * <p>{@code $inc} with the negative sum per issue, for the reason the write path
	 * uses it: the counter is the sum of the entries, and recomputing it while
	 * somebody logs time on the same issue would lose their minutes. One bulk write
	 * per batch rather than a round trip per issue.
	 */
	private void delete(List<Row> doomed, TimeRetentionRun run) {
		if (doomed.isEmpty()) {
			return;
		}
		List<ObjectId> ids = doomed.stream().map(Row::id).toList();
		long removed = mongo.remove(Query.query(Criteria.where("_id").in(ids)), WorkItem.class)
				.getDeletedCount();
		run.setEntriesDeleted(run.getEntriesDeleted() + removed);
		Map<String, Integer> minutesByIssue = new HashMap<>();
		for (Row row : doomed) {
			if (row.issueId() != null && row.minutes() != 0) {
				minutesByIssue.merge(row.issueId(), row.minutes(), Integer::sum);
			}
		}
		if (minutesByIssue.isEmpty()) {
			return;
		}
		BulkOperations counters = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Issue.class);
		minutesByIssue.forEach((issueId, minutes) -> counters.updateOne(
				Query.query(Criteria.where("_id").is(issueId)),
				new Update().inc("spentMinutes", -minutes)));
		counters.execute();
	}

	private void saveCursor(LocalDate day, ObjectId after) {
		mongo.save(TimeRetentionRun.builder().id(ENTRY_CURSOR).cursorDay(day)
				.cursorId(after == null ? null : after.toHexString()).build());
	}

	/** Writes the counters as they stand, so a run that dies still says what it did. */
	private void progress(TimeRetentionRun run) {
		try {
			mongo.save(run);
		}
		catch (RuntimeException ex) {
			log.warn("[time] could not record retention progress: {}", ex.toString());
		}
	}

	/** A work-item query read as raw documents; see {@link WorkItemDocuments}. */
	private List<Document> raw(Query query) {
		return mongo.query(WorkItem.class).as(Document.class).matching(query).all();
	}

	/**
	 * The submitted and approved periods that began before the cutoff, as intervals by
	 * person and project — read once per run.
	 */
	private static final class FrozenPeriods {

		private final Map<String, List<LocalDate[]>> byPair;

		private FrozenPeriods(Map<String, List<LocalDate[]>> byPair) {
			this.byPair = byPair;
		}

		static FrozenPeriods load(MongoTemplate mongo, LocalDate before) {
			Query query = Query.query(Criteria.where("status")
					.in(TimesheetApproval.Status.SUBMITTED, TimesheetApproval.Status.APPROVED)
					.and("periodStart").lt(before));
			query.fields().include("userId", "projectId", "periodStart", "periodEnd");
			Map<String, List<LocalDate[]>> byPair = new HashMap<>();
			mongo.query(TimesheetApproval.class).as(Document.class).matching(query).all()
					.forEach(approval -> byPair
							.computeIfAbsent(key(approval.getString("userId"), approval.getString("projectId")),
									ignored -> new ArrayList<>())
							.add(new LocalDate[]{WorkItemDocuments.day(approval, "periodStart"),
									WorkItemDocuments.day(approval, "periodEnd")}));
			return new FrozenPeriods(byPair);
		}

		/** Whether a submitted or approved period of this person and project holds {@code day}. */
		boolean holds(String userId, String projectId, LocalDate day) {
			if (userId == null || projectId == null) {
				return false;
			}
			List<LocalDate[]> periods = byPair.get(key(userId, projectId));
			if (periods == null) {
				return false;
			}
			for (LocalDate[] period : periods) {
				if (period[0] != null && period[1] != null && !day.isBefore(period[0])
						&& !day.isAfter(period[1])) {
					return true;
				}
			}
			return false;
		}

		private static String key(String userId, String projectId) {
			return userId + '|' + projectId;
		}
	}
}
