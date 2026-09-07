package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Logged time on issues: who may read and change which entries, what a valid
 * entry is, and the one derived number that depends on them —
 * {@code Issue.spentMinutes}.
 *
 * <p>Every read and write here is scoped by the caller: entries of an issue are
 * visible to the issue's project members (the same rule as the issue itself),
 * an entry is edited or deleted by its owner, by a lead of its project, or by
 * an admin, and a timesheet shows a user their own hours unless they are an
 * admin. REST and MCP both go through here, so there is exactly one place the
 * rule lives.
 */
@Service
@RequiredArgsConstructor
public class TimeTrackingService {

	/** The array-shaped per-issue list is capped; the paged route has no such limit. */
	public static final int LIST_CAP = 200;
	/** Largest page the paged route hands out. */
	public static final int PAGE_MAX = 100;
	/** Longest a single entry may be: one day. */
	public static final int MAX_MINUTES = 24 * 60;
	/** How far back an entry may be dated, in days. */
	public static final int MAX_DAYS_BACK = 365;
	/** Longest timesheet range, in days. */
	public static final int MAX_RANGE_DAYS = 92;

	private static final String DEFAULT_ACTIVITY = "Development";

	private final WorkItemRepository workItems;
	private final IssueService issues;
	private final ProjectService projects;
	private final ProjectReach projectReach;
	private final UserRepository users;
	private final MongoTemplate mongo;
	private final AuditService audit;
	private final SettingsService settings;
	private final Clock clock;

	// --- shapes ------------------------------------------------------------

	/**
	 * What a caller may say when logging time. Everything is optional except
	 * that a duration has to come from somewhere: either {@code durationMinutes}
	 * or a complete {@code startedAt}/{@code endedAt} pair, which then wins and
	 * also supplies the day when none is given.
	 */
	public record NewWorkItem(Integer durationMinutes, LocalDate date, String activityType,
			String description, Instant startedAt, Instant endedAt, List<String> tags,
			Boolean billable) {
	}

	/**
	 * A partial edit. A null field means "leave it"; the two {@code *Set} flags
	 * carry the difference between an absent {@code startedAt} and an explicit
	 * null, which clears it.
	 */
	public record WorkItemPatch(Integer durationMinutes, LocalDate date, String activityType,
			String description, boolean startedAtSet, Instant startedAt, boolean endedAtSet,
			Instant endedAt, List<String> tags, Boolean billable) {
	}

	public record TimesheetRow(String userId, String projectId, Map<LocalDate, Integer> minutesPerDay,
			int totalMinutes) {
	}

	// --- reads -------------------------------------------------------------

	/** The issue behind an id or readable id, if {@code user} may see it (403 otherwise). */
	public Issue resolveIssue(String issueIdOrKey, User user) {
		return issues.getForUser(issueIdOrKey, user);
	}

	/** An issue's entries, newest first, capped at {@link #LIST_CAP}. */
	public List<WorkItem> list(String issueIdOrKey, User user) {
		Issue issue = resolveIssue(issueIdOrKey, user);
		return mongo.find(Query.query(Criteria.where("issueId").is(issue.getId()))
				.with(WorkItemRepository.NEWEST_FIRST).limit(LIST_CAP), WorkItem.class);
	}

	/** One page of an issue's entries, newest first; the size is clamped to {@link #PAGE_MAX}. */
	public Page<WorkItem> page(String issueIdOrKey, int page, int size, User user) {
		Issue issue = resolveIssue(issueIdOrKey, user);
		return workItems.findByIssueId(issue.getId(), PageRequest.of(Math.max(page, 0),
				Math.clamp(size, 1, PAGE_MAX), WorkItemRepository.NEWEST_FIRST));
	}

	// --- writes ------------------------------------------------------------

	/**
	 * Logs time on an issue for {@code user}, who must be a member of its
	 * project. {@code source} says how the entry came about (app, MCP, smart
	 * commit); the entry is always owned by {@code user}.
	 */
	public WorkItem add(String issueIdOrKey, NewWorkItem draft, WorkItem.Source source, User user) {
		Issue issue = resolveIssue(issueIdOrKey, user);
		ZoneId zone = zoneOf(user);
		Instant start = draft.startedAt();
		Instant end = draft.endedAt();
		int minutes = resolveDuration(draft.durationMinutes(), start, end);
		LocalDate date = draft.date() != null ? draft.date()
				: LocalDate.ofInstant(start != null ? start : clock.instant(), zone);
		validateDate(date, zone);
		WorkItem item = WorkItem.builder()
				.issueId(issue.getId())
				.projectId(issue.getProjectId())
				.userId(user.getId())
				.date(date)
				.durationMinutes(minutes)
				.activityType(activityOrDefault(draft.activityType()))
				.description(draft.description())
				.startedAt(start)
				.endedAt(end)
				.billable(Boolean.TRUE.equals(draft.billable()))
				.tags(normalizeTags(draft.tags()))
				.source(source == null ? WorkItem.Source.APP : source)
				.build();
		WorkItem saved = workItems.save(item);
		syncSpentTime(issue.getId());
		return saved;
	}

	/**
	 * Edits an entry. The owner may always; a lead of the entry's project or an
	 * admin may too, and that is audited because it touches someone else's
	 * record of their own work.
	 */
	public WorkItem update(String workItemId, WorkItemPatch patch, User user) {
		WorkItem item = workItems.findById(workItemId)
				.orElseThrow(() -> ApiException.notFound("workItem"));
		boolean own = isOwner(item, user);
		if (!own && !canManageForeign(item, user)) {
			throw ApiException.forbidden("error.time.editOwnOnly");
		}
		if (patch.startedAtSet()) {
			item.setStartedAt(patch.startedAt());
		}
		if (patch.endedAtSet()) {
			item.setEndedAt(patch.endedAt());
		}
		boolean intervalTouched = patch.startedAtSet() || patch.endedAtSet();
		if (intervalTouched && item.getStartedAt() != null && item.getEndedAt() != null) {
			item.setDurationMinutes(resolveDuration(null, item.getStartedAt(), item.getEndedAt()));
		}
		else if (patch.durationMinutes() != null) {
			item.setDurationMinutes(resolveDuration(patch.durationMinutes(), null, null));
		}
		if (patch.date() != null) {
			// Only a day that is being set is checked: an entry older than the
			// window must stay editable in its other fields.
			validateDate(patch.date(), zoneOf(user));
			item.setDate(patch.date());
		}
		if (patch.activityType() != null) {
			item.setActivityType(activityOrDefault(patch.activityType()));
		}
		if (patch.description() != null) {
			item.setDescription(patch.description());
		}
		if (patch.tags() != null) {
			item.setTags(normalizeTags(patch.tags()));
		}
		if (patch.billable() != null) {
			item.setBillable(patch.billable());
		}
		item.setUpdatedAt(clock.instant());
		item.setUpdatedBy(user.getId());
		WorkItem saved = workItems.save(item);
		if (saved.getIssueId() != null) {
			syncSpentTime(saved.getIssueId());
		}
		if (!own) {
			audit(AuditAction.TIME_ENTRY_UPDATED, saved, user);
		}
		return saved;
	}

	/** Deletes an entry: the owner, a lead of its project, or an admin (audited when foreign). */
	public void delete(String workItemId, User user) {
		delete(workItemId, user, true);
	}

	/**
	 * Deletes one of the caller's <em>own</em> entries only — the MCP tool's
	 * contract, which never elevates a token holder to a lead or an admin.
	 */
	public void deleteOwn(String workItemId, User user) {
		delete(workItemId, user, false);
	}

	private void delete(String workItemId, User user, boolean allowForeign) {
		WorkItem item = workItems.findById(workItemId)
				.orElseThrow(() -> ApiException.notFound("workItem"));
		boolean own = isOwner(item, user);
		if (!own && !(allowForeign && canManageForeign(item, user))) {
			throw ApiException.forbidden("error.time.deleteOwnOnly");
		}
		workItems.delete(item);
		if (item.getIssueId() != null) {
			syncSpentTime(item.getIssueId());
		}
		if (!own) {
			audit(AuditAction.TIME_ENTRY_DELETED, item, user);
		}
	}

	/** Whether {@code user} may edit or delete an entry that is not their own: admin or project lead. */
	public boolean canManageForeign(WorkItem item, User user) {
		if (user.isAdmin()) {
			return true;
		}
		if (item.getProjectId() == null) {
			return false;
		}
		return projects.findOptional(item.getProjectId())
				.map(project -> projects.isLeadOrAdmin(project, user))
				.orElse(false);
	}

	private static boolean isOwner(WorkItem item, User user) {
		return item.getUserId() != null && item.getUserId().equals(user.getId());
	}

	private void audit(AuditAction action, WorkItem item, User actor) {
		User owner = item.getUserId() == null ? null : users.findById(item.getUserId()).orElse(null);
		AuditService.Entry entry = audit.event(action).actor(actor)
				.meta("workItem", item.getId())
				.meta("issue", item.getIssueId())
				.meta("project", item.getProjectId())
				.meta("owner", item.getUserId())
				.meta("minutes", String.valueOf(item.getDurationMinutes()))
				.meta("date", String.valueOf(item.getDate()));
		if (owner != null) {
			entry.target(owner);
		}
		else if (item.getUserId() != null) {
			entry.target(item.getUserId(), item.getUserId());
		}
		entry.log();
	}

	// --- the derived counter -------------------------------------------------

	/**
	 * {@code Issue.spentMinutes} is the sum of the issue's entries, and this is
	 * the only place that writes it. Summed by Mongo and written with a targeted
	 * {@code $set} — never by saving the whole issue, which would overwrite
	 * whatever anyone else changed on it between the read and the write.
	 */
	public void syncSpentTime(String issueId) {
		if (issueId == null) {
			return;
		}
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("issueId").is(issueId)),
				Aggregation.group().sum("durationMinutes").as("total"));
		Document row = mongo.aggregate(aggregation, WorkItem.class, Document.class)
				.getUniqueMappedResult();
		int total = row != null && row.get("total") instanceof Number number ? number.intValue() : 0;
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issueId)),
				new Update().set("spentMinutes", total).currentDate("updatedAt"), Issue.class);
	}

	// --- timesheet -------------------------------------------------------------

	/**
	 * Timesheet matrix: per user+project row, minutes per day in the range.
	 *
	 * <p>Scoped by {@code requester}: an admin may filter by any user and any
	 * project or by nothing at all; everyone else gets their own rows — a foreign
	 * {@code userId} is refused, not silently replaced — and may narrow them to
	 * a project they can see. Both filters are applied together, so a project
	 * filter can never widen a user filter.
	 */
	public List<TimesheetRow> timesheet(LocalDate from, LocalDate to, String userId, String projectId,
			User requester) {
		if (from.isAfter(to) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
			throw ApiException.badRequest("error.time.invalidRange");
		}
		String effectiveUser = userId;
		if (!requester.isAdmin()) {
			if (userId == null) {
				effectiveUser = requester.getId();
			}
			else if (!userId.equals(requester.getId())) {
				throw ApiException.forbidden("error.accessDenied");
			}
		}
		if (projectId != null && !projectReach.canSee(projectId, requester)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		Criteria criteria = Criteria.where("date").gte(from).lte(to);
		if (effectiveUser != null) {
			criteria = criteria.and("userId").is(effectiveUser);
		}
		if (projectId != null) {
			criteria = criteria.and("projectId").is(projectId);
		}
		return rowsOf(criteria);
	}

	/**
	 * The matrix itself: Mongo sums the minutes, this assembles the rows.
	 *
	 * <p>Summing here rather than in Java is what keeps the endpoint usable at
	 * the 92 days it allows. An unfiltered quarter in a two-hundred-person
	 * instance matches on the order of eighty thousand entries and answers with
	 * a few thousand rows; loading the entries to add them up meant carrying all
	 * eighty thousand as objects to emit the same numbers. What comes back now
	 * is one figure per person, project and day — the shape of the answer, not
	 * the shape of the data behind it — and the JSON is unchanged, which the
	 * published app depends on.
	 *
	 * <p>The day is grouped as a formatted string rather than the stored value,
	 * so an entry whose date somehow carries a time of day still lands on its
	 * own day instead of opening a second bucket. {@code $dateToString} reads
	 * UTC by default, which is the zone {@code date} is written in.
	 */
	private List<TimesheetRow> rowsOf(Criteria criteria) {
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(criteria),
				Aggregation.project("userId", "projectId", "durationMinutes")
						.and(DateOperators.dateOf("date").toString("%Y-%m-%d")).as("day"),
				Aggregation.group("userId", "projectId", "day")
						.sum("durationMinutes").as("minutes"));
		// A record key rather than "userId|projectId": either side may be null
		// (legacy entries carry no user, detached entries may carry no project)
		// and a null must group with nulls, not with the literal text "null".
		record RowKey(String userId, String projectId) {
		}
		Map<RowKey, Map<LocalDate, Integer>> rowsByKey = new LinkedHashMap<>();
		for (Document group : mongo.aggregate(aggregation, WorkItem.class, Document.class)) {
			Document key = group.get("_id", Document.class);
			String day = key == null ? null : key.getString("day");
			if (day == null) {
				continue; // an entry with no date at all belongs to no column
			}
			int minutes = group.get("minutes") instanceof Number sum ? sum.intValue() : 0;
			rowsByKey.computeIfAbsent(
					new RowKey(key.getString("userId"), key.getString("projectId")),
					unused -> new TreeMap<>())
					.merge(LocalDate.parse(day), minutes, Integer::sum);
		}
		List<TimesheetRow> rows = new ArrayList<>();
		rowsByKey.forEach((key, perDay) -> rows.add(new TimesheetRow(key.userId(), key.projectId(),
				perDay, perDay.values().stream().mapToInt(Integer::intValue).sum())));
		rows.sort(Comparator.comparing(TimesheetRow::userId, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(TimesheetRow::projectId, Comparator.nullsLast(Comparator.naturalOrder())));
		return rows;
	}

	// --- rules -------------------------------------------------------------------

	/** The zone "today" and a timer's day are read in for this user. */
	public ZoneId zoneOf(User user) {
		return UserZones.of(user, settings.get());
	}

	/**
	 * A complete start/end pair defines the duration and must span between one
	 * minute and one day; otherwise the given minutes must, and they must be
	 * given.
	 */
	static int resolveDuration(Integer given, Instant start, Instant end) {
		if (start != null && end != null) {
			long minutes = Duration.between(start, end).toMinutes();
			if (minutes < 1 || minutes > MAX_MINUTES) {
				throw ApiException.badRequest("error.time.invalidDuration");
			}
			return (int) minutes;
		}
		if (given == null || given < 1 || given > MAX_MINUTES) {
			throw ApiException.badRequest("error.time.invalidDuration");
		}
		return given;
	}

	/** Not after today and not more than a year back — today being the user's today, not the server's. */
	private void validateDate(LocalDate date, ZoneId zone) {
		LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
		if (date.isAfter(today)) {
			throw ApiException.badRequest("error.time.dateInFuture");
		}
		if (date.isBefore(today.minusDays(MAX_DAYS_BACK))) {
			throw ApiException.badRequest("error.time.dateTooOld");
		}
	}

	private static String activityOrDefault(String activityType) {
		return activityType == null || activityType.isBlank() ? DEFAULT_ACTIVITY : activityType.trim();
	}

	/** Trimmed, no blanks, no duplicates, order kept. Size limits are the request's job. */
	static List<String> normalizeTags(List<String> tags) {
		if (tags == null) {
			return new ArrayList<>();
		}
		LinkedHashSet<String> cleaned = new LinkedHashSet<>();
		for (String tag : tags) {
			if (tag != null && !tag.isBlank()) {
				cleaned.add(tag.trim());
			}
		}
		return new ArrayList<>(cleaned);
	}
}
