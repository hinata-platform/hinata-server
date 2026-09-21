package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The numbers behind the time reports (HIN-93): totals and groups ({@link #summary}), the entries
 * themselves ({@link #detailed}), and the query the exports stream.
 *
 * <p>Every answer is computed in the reader's scope, in the query ({@link TimeReportScope}). A
 * group or a filter that narrows down to a person is answered over the entries whose people the
 * reader may see; a project's totals over everything the reader can see of it.
 *
 * <p>Rounding applies to each entry before anything is added up — the way toggl rounds, and the
 * only way a report and the invoice later written from it agree line by line. It happens inside
 * the pipeline ({@link #roundedMinutes}), so a year of entries is never read into memory to be
 * rounded. What was filed is never changed; {@link Totals#filedMinutes} carries it alongside.
 *
 * <p>Days are the days entries were filed for. Week and month buckets are cut from those days on
 * the UTC calendar they are stored on, never shifted by a reader's zone — shifting a stored day
 * is how an entry on the 1st lands in the previous month for a reader west of Greenwich.
 *
 * <p>The legacy {@code /api/v1/reports/time-per-*} routes keep their own aggregation in
 * {@code report.ReportService}: they answer with the module switched off, and the core may not
 * call into the module (ModuleBoundaryTest).
 */
@Service
@RequiredArgsConstructor
public class TimeReportService {

	/** Largest page of groups or entries. */
	public static final int PAGE_MAX = 100;

	/** Most people one report groups by name; their order is settled here, by name. */
	static final int PEOPLE_GROUPS_MAX = 10_000;

	/** Most submitted periods an approval filter turns into conditions. */
	static final int APPROVALS_MAX = 2_000;

	/** Most entries a detailed report counts; its pages stop there too. */
	static final int COUNT_MAX = 100_000;

	/** What an entry written before activities existed counts as. */
	static final String DEFAULT_ACTIVITY = "Development";

	/** A condition no entry meets: every stored entry has an id. */
	private static final Criteria NOTHING = Criteria.where("_id").exists(false);

	private final MongoTemplate mongo;
	private final TimeReportScope scopes;
	private final TimeTrackingSettings policy;
	private final SettingsService settings;

	/** What a summary is grouped by. People and teams are read only where their people are visible. */
	public enum GroupBy {
		PROJECT, USER, TEAM, ACTIVITY, TAG, ISSUE, DAY, WEEK, MONTH;

		boolean isTime() {
			return this == DAY || this == WEEK || this == MONTH;
		}

		boolean readsPeople() {
			return this == USER || this == TEAM;
		}
	}

	/**
	 * @param minutes      rounded per entry, then added up
	 * @param filedMinutes as filed, added up
	 */
	public record Totals(long minutes, long filedMinutes, long billableMinutes, long entries) {

		static final Totals NONE = new Totals(0, 0, 0, 0);
	}

	/**
	 * One group of a summary.
	 *
	 * @param key    the id, the name or the first day of the bucket; null for "none" — no
	 *               project, no issue, no tag, no team
	 * @param label  what the key reads as: a project's name, a person's name, an issue's title;
	 *               null where the key is its own label or the record is gone
	 * @param detail a second line: a project's key, an issue's readable id, a person's username
	 */
	public record Group(String key, String label, String detail, long minutes, long billableMinutes,
			long entries) {
	}

	/**
	 * @param people whether the reader may see anybody's entries but their own — a client offers
	 *               grouping by person only then
	 */
	public record Summary(Totals totals, GroupBy groupBy, Page<Group> groups, boolean people) {
	}

	/** One entry of a detailed report, with the names it points at already resolved. */
	public record EntryRow(String id, LocalDate date, Instant startedAt, Instant endedAt, int minutes,
			int roundedMinutes, String userId, String userName, String projectId, String projectKey,
			String projectName, String issueId, String issueKey, String issueTitle, String activity,
			String description, List<String> tags, boolean billable, WorkItem.Source source) {
	}

	/** [query] checked, with the policy's rounding and the reader's zone where it names none. */
	public TimeReportFilter filter(User viewer, TimeReportQuery query) {
		return TimeReportFilter.of(query.from(), query.to(), query.projectIds(), query.userIds(), query.teamIds(),
				query.tags(), query.billable(), query.activities(), query.q(), query.approval(), query.rounding(),
				query.roundingIncrement(), policy.rounding(), query.tz(), UserZones.of(viewer, settings.get()));
	}

	// --- summary ------------------------------------------------------------

	public Summary summary(User viewer, TimeReportFilter filter, GroupBy groupBy, int page, int size) {
		TimeReportScope.Reach reach = scopes.of(viewer);
		boolean people = groupBy.readsPeople() || filter.readsPeople();
		Criteria criteria = criteria(reach, filter, people);
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX));
		Page<Group> groups = groupBy == GroupBy.USER
				? byName(criteria, filter, pageable)
				: grouped(criteria, filter, groupBy, pageable);
		return new Summary(totals(criteria, filter), groupBy, groups, reach.seesOthers());
	}

	/** The totals of everything [criteria] matches, in a pipeline of their own. */
	Totals totals(Criteria criteria, TimeReportFilter filter) {
		List<AggregationOperation> stages = new ArrayList<>();
		stages.add(Aggregation.match(criteria));
		stages.add(stage("$addFields", new Document("m", roundedMinutes(filter.rounding()))));
		stages.add(stage("$group", new Document("_id", null)
				.append("minutes", new Document("$sum", "$m"))
				.append("filed", new Document("$sum", new Document("$ifNull", List.of("$durationMinutes", 0))))
				.append("billable", new Document("$sum", billableOf("$m")))
				.append("entries", new Document("$sum", 1))));
		Document row = aggregate(stages).stream().findFirst().orElse(null);
		return row == null ? Totals.NONE
				: new Totals(number(row, "minutes"), number(row, "filed"), number(row, "billable"),
						number(row, "entries"));
	}

	/** One page of groups, sorted in the pipeline: buckets by date, everything else by size. */
	private Page<Group> grouped(Criteria criteria, TimeReportFilter filter, GroupBy groupBy, Pageable pageable) {
		List<AggregationOperation> stages = groupStages(criteria, filter, groupBy);
		Document order = groupBy.isTime() ? new Document("_id", 1)
				: new Document("minutes", -1).append("_id", 1);
		stages.add(stage("$facet", new Document("rows", List.of(
						new Document("$sort", order),
						new Document("$skip", pageable.getOffset()),
						new Document("$limit", pageable.getPageSize())))
				.append("count", List.of(new Document("$count", "n")))));
		Document facet = aggregate(stages).stream().findFirst().orElse(new Document());
		List<Document> rows = facet.getList("rows", Document.class, List.of());
		List<Document> counted = facet.getList("count", Document.class, List.of());
		long total = counted.isEmpty() ? 0 : number(counted.get(0), "n");
		return new PageImpl<>(label(groupBy, rows), pageable, total);
	}

	/**
	 * Groups by person, in order of name — never of hours. A list of colleagues ranked by how much
	 * they booked is a ranking nobody asked for (R10), so the pipeline only adds up, and the order
	 * is settled here after the names are known.
	 */
	private Page<Group> byName(Criteria criteria, TimeReportFilter filter, Pageable pageable) {
		List<AggregationOperation> stages = groupStages(criteria, filter, GroupBy.USER);
		stages.add(stage("$limit", PEOPLE_GROUPS_MAX + 1));
		List<Document> rows = aggregate(stages);
		if (rows.size() > PEOPLE_GROUPS_MAX) {
			throw ApiException.badRequest("error.time.report.tooBroad");
		}
		List<Group> named = new ArrayList<>(label(GroupBy.USER, rows));
		Collator collator = Collator.getInstance(Locale.ENGLISH);
		collator.setStrength(Collator.SECONDARY);
		named.sort(Comparator.comparing((Group group) -> group.label() == null ? "￿" : group.label(), collator)
				.thenComparing(group -> Objects.toString(group.key(), "")));
		int from = (int) Math.min(pageable.getOffset(), named.size());
		int to = Math.min(from + pageable.getPageSize(), named.size());
		return new PageImpl<>(named.subList(from, to), pageable, named.size());
	}

	private List<AggregationOperation> groupStages(Criteria criteria, TimeReportFilter filter, GroupBy groupBy) {
		List<AggregationOperation> stages = new ArrayList<>();
		stages.add(Aggregation.match(criteria));
		stages.add(stage("$addFields", new Document("m", roundedMinutes(filter.rounding()))));
		Object key = switch (groupBy) {
			case PROJECT -> "$projectId";
			case USER -> "$userId";
			case ISSUE -> "$issueId";
			case ACTIVITY -> new Document("$ifNull", List.of("$activityType", DEFAULT_ACTIVITY));
			case TAG -> {
				stages.add(stage("$unwind", new Document("path", "$tags").append("preserveNullAndEmptyArrays", true)));
				yield "$tags";
			}
			case TEAM -> {
				// Membership is the only tie between an entry and a team, and somebody in two
				// teams counts in both: the groups add up to more than the total, which is why
				// the total comes from a pipeline of its own.
				stages.add(stage("$lookup", new Document("from", mongo.getCollectionName(Team.class))
						.append("localField", "userId")
						.append("foreignField", "members.userId")
						.append("pipeline", List.of(new Document("$project", new Document("_id", 1))))
						.append("as", "team")));
				stages.add(stage("$unwind", new Document("path", "$team").append("preserveNullAndEmptyArrays", true)));
				yield "$team._id";
			}
			case DAY -> "$date";
			case WEEK -> new Document("$dateTrunc", new Document("date", "$date").append("unit", "week")
					.append("startOfWeek", weekStart().name().toLowerCase(Locale.ROOT)).append("timezone", "UTC"));
			case MONTH -> new Document("$dateTrunc", new Document("date", "$date").append("unit", "month")
					.append("timezone", "UTC"));
		};
		stages.add(stage("$group", new Document("_id", key)
				.append("minutes", new Document("$sum", "$m"))
				.append("billable", new Document("$sum", billableOf("$m")))
				.append("entries", new Document("$sum", 1))));
		return stages;
	}

	/** The groups of one page with their names. Each kind is looked up once per page. */
	private List<Group> label(GroupBy groupBy, List<Document> rows) {
		List<String> keys = rows.stream().map(row -> keyOf(row.get("_id"))).toList();
		Set<String> ids = keys.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, Document> named = switch (groupBy) {
			case PROJECT -> documents(Project.class, ids, "key", "name");
			case USER -> documents(User.class, ids, "displayName", "username");
			case ISSUE -> documents(Issue.class, ids, "readableId", "title");
			case TEAM -> documents(Team.class, ids, "name", "key");
			default -> Map.of();
		};
		List<Group> groups = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			Document row = rows.get(i);
			String key = keys.get(i);
			Document found = key == null ? null : named.get(key);
			String label = null;
			String detail = null;
			if (found != null) {
				switch (groupBy) {
					case PROJECT -> {
						label = found.getString("name");
						detail = found.getString("key");
					}
					case USER -> {
						label = firstNonBlank(found.getString("displayName"), found.getString("username"));
						detail = found.getString("username");
					}
					case ISSUE -> {
						label = found.getString("title");
						detail = found.getString("readableId");
					}
					case TEAM -> {
						label = found.getString("name");
						detail = found.getString("key");
					}
					default -> {
					}
				}
			}
			groups.add(new Group(key, label, detail, number(row, "minutes"), number(row, "billable"),
					number(row, "entries")));
		}
		return groups;
	}

	/**
	 * Minutes booked by each of [userIds] in the window, rounded per entry — over the entries whose
	 * people the reader sees, so a lead's figure for somebody is their time on the lead's projects.
	 */
	Map<String, Long> minutesByPerson(TimeReportScope.Reach reach, TimeReportFilter filter,
			Collection<String> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		List<AggregationOperation> stages = new ArrayList<>();
		stages.add(Aggregation.match(new Criteria().andOperator(criteria(reach, filter, true),
				Criteria.where("userId").in(userIds))));
		stages.add(stage("$group", new Document("_id", "$userId")
				.append("minutes", new Document("$sum", roundedMinutes(filter.rounding())))));
		Map<String, Long> minutes = new HashMap<>();
		for (Document row : aggregate(stages)) {
			if (row.get("_id") != null) {
				minutes.put(row.get("_id").toString(), number(row, "minutes"));
			}
		}
		return minutes;
	}

	// --- detailed -----------------------------------------------------------

	/** The entries themselves, newest first — always over the entries whose people the reader sees. */
	public Page<EntryRow> detailed(User viewer, TimeReportFilter filter, int page, int size) {
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX));
		if (pageable.getOffset() >= COUNT_MAX) {
			return new PageImpl<>(List.of(), pageable, COUNT_MAX);
		}
		Query query = detailedQuery(viewer, filter);
		List<WorkItem> items = mongo.find(Query.of(query).with(pageable), WorkItem.class);
		return PageableExecutionUtils.getPage(rows(items, filter), pageable,
				() -> mongo.count(Query.of(query).limit(COUNT_MAX), WorkItem.class));
	}

	/**
	 * The query a detailed report and every export read: the reader's people scope, the filter,
	 * newest first. Day, then id — an order the {@code date_id} index walks without sorting, and
	 * within a day the order entries were written in.
	 */
	Query detailedQuery(User viewer, TimeReportFilter filter) {
		Criteria criteria = criteria(scopes.of(viewer), filter, true);
		return Query.query(criteria).with(Sort.by(Sort.Order.desc("date"), Sort.Order.desc("_id")));
	}

	/** [items] as rows, with every user, project and issue they name looked up once. */
	List<EntryRow> rows(List<WorkItem> items, TimeReportFilter filter) {
		Map<String, Document> users = documents(User.class, idsOf(items, WorkItem::getUserId), "displayName", "username");
		Map<String, Document> projects = documents(Project.class, idsOf(items, WorkItem::getProjectId), "key", "name");
		Map<String, Document> issues = documents(Issue.class, idsOf(items, WorkItem::getIssueId), "readableId", "title");
		List<EntryRow> rows = new ArrayList<>(items.size());
		for (WorkItem item : items) {
			Document user = item.getUserId() == null ? null : users.get(item.getUserId());
			Document project = item.getProjectId() == null ? null : projects.get(item.getProjectId());
			Document issue = item.getIssueId() == null ? null : issues.get(item.getIssueId());
			rows.add(new EntryRow(item.getId(), item.getDate(), item.getStartedAt(), item.getEndedAt(),
					item.getDurationMinutes(), TimeRounding.round(item.getDurationMinutes(), filter.rounding()),
					item.getUserId(),
					user == null ? null : firstNonBlank(user.getString("displayName"), user.getString("username")),
					item.getProjectId(), project == null ? null : project.getString("key"),
					project == null ? null : project.getString("name"),
					item.getIssueId(), issue == null ? null : issue.getString("readableId"),
					issue == null ? null : issue.getString("title"),
					item.getActivityType() == null ? DEFAULT_ACTIVITY : item.getActivityType(),
					item.getDescription(), item.getTags(), item.isBillable(), item.getSource()));
		}
		return rows;
	}

	// --- the query ----------------------------------------------------------

	/** The reader's scope and the filter, as one condition. */
	Criteria criteria(TimeReportScope.Reach reach, TimeReportFilter filter, boolean people) {
		List<Criteria> all = new ArrayList<>();
		all.add(people ? reach.people() : reach.totals());
		all.add(Criteria.where("date").gte(filter.from()).lte(filter.to()));
		if (!filter.projectIds().isEmpty()) {
			all.add(Criteria.where("projectId").in(filter.projectIds()));
		}
		if (!filter.userIds().isEmpty()) {
			all.add(Criteria.where("userId").in(filter.userIds()));
		}
		if (!filter.teamIds().isEmpty()) {
			all.add(Criteria.where("userId").in(membersOf(filter.teamIds())));
		}
		if (!filter.tags().isEmpty()) {
			all.add(Criteria.where("tags").in(filter.tags()));
		}
		if (filter.billable() != null) {
			// An entry written before billing existed carries no flag and was never billable.
			all.add(filter.billable() ? Criteria.where("billable").is(true) : Criteria.where("billable").ne(true));
		}
		if (!filter.activities().isEmpty()) {
			all.add(filter.activities().contains(DEFAULT_ACTIVITY)
					? new Criteria().orOperator(Criteria.where("activityType").in(filter.activities()),
							Criteria.where("activityType").is(null))
					: Criteria.where("activityType").in(filter.activities()));
		}
		if (filter.description() != null) {
			all.add(Criteria.where("description")
					.regex(Pattern.compile(Pattern.quote(filter.description()), Pattern.CASE_INSENSITIVE)));
		}
		if (!filter.approval().isEmpty()) {
			all.add(approval(filter));
		}
		return new Criteria().andOperator(all);
	}

	/** The members of [teamIds]; an entry's person is the only thing that ties it to a team. */
	private Set<String> membersOf(List<String> teamIds) {
		Query query = Query.query(Criteria.where("_id").in(teamIds));
		query.fields().include("members.userId");
		Set<String> members = new LinkedHashSet<>();
		for (Document team : mongo.query(Team.class).as(Document.class).matching(query).all()) {
			for (Document member : team.getList("members", Document.class, List.of())) {
				if (member.getString("userId") != null) {
					members.add(member.getString("userId"));
				}
			}
		}
		return members;
	}

	/**
	 * The entries whose period for their person and project is in one of the asked states. A
	 * submission is a stored span, so the periods touching the window are read first and turned
	 * into conditions — containment, never a recomputed grid (HIN-88).
	 */
	private Criteria approval(TimeReportFilter filter) {
		Set<TimesheetApproval.Status> wanted = new LinkedHashSet<>();
		for (TimeReportFilter.Approval state : filter.approval()) {
			switch (state) {
				case SUBMITTED -> wanted.add(TimesheetApproval.Status.SUBMITTED);
				case APPROVED -> wanted.add(TimesheetApproval.Status.APPROVED);
				case REJECTED -> wanted.add(TimesheetApproval.Status.REJECTED);
				case OPEN -> {
				}
			}
		}
		boolean open = filter.approval().contains(TimeReportFilter.Approval.OPEN);
		Criteria which = Criteria.where("status").in(TimesheetApproval.Status.SUBMITTED,
						TimesheetApproval.Status.APPROVED, TimesheetApproval.Status.REJECTED)
				.and("periodEnd").gte(filter.from()).and("periodStart").lte(filter.to());
		if (!filter.userIds().isEmpty()) {
			which = which.and("userId").in(filter.userIds());
		}
		if (!filter.projectIds().isEmpty()) {
			which = which.and("projectId").in(filter.projectIds());
		}
		Query query = Query.query(which).limit(APPROVALS_MAX + 1);
		query.fields().include("userId").include("projectId").include("periodStart").include("periodEnd")
				.include("status");
		List<Document> periods = mongo.query(TimesheetApproval.class).as(Document.class).matching(query).all();
		if (periods.size() > APPROVALS_MAX) {
			throw ApiException.badRequest("error.time.report.tooBroad");
		}
		List<Criteria> every = new ArrayList<>();
		List<Criteria> selected = new ArrayList<>();
		for (Document period : periods) {
			LocalDate start = WorkItemDocuments.day(period, "periodStart");
			LocalDate end = WorkItemDocuments.day(period, "periodEnd");
			if (start == null || end == null) {
				continue;
			}
			Criteria inside = Criteria.where("userId").is(period.getString("userId"))
					.and("projectId").is(period.getString("projectId"))
					.and("date").gte(start.isBefore(filter.from()) ? filter.from() : start)
					.lte(end.isAfter(filter.to()) ? filter.to() : end);
			every.add(inside);
			if (wanted.contains(TimesheetApproval.Status.valueOf(period.getString("status")))) {
				selected.add(inside);
			}
		}
		Criteria inSelected = selected.isEmpty() ? NOTHING : new Criteria().orOperator(selected);
		if (!open) {
			return inSelected;
		}
		Criteria outside = every.isEmpty() ? new Criteria() : new Criteria().norOperator(every);
		return wanted.isEmpty() ? outside : new Criteria().orOperator(outside, inSelected);
	}

	/**
	 * An entry's minutes folded by [rounding], as a pipeline expression — {@link TimeRounding}
	 * written for Mongo, so a year of entries is rounded where it lies. The remainder is a floor
	 * modulo, as there: a negative duration folds downwards like a positive one.
	 */
	static Object roundedMinutes(TimeTrackingSettings.Rounding rounding) {
		Document minutes = new Document("$ifNull", List.of("$durationMinutes", 0));
		if (rounding == null || rounding.mode() == null || rounding.mode() == TimePolicy.Rounding.NONE
				|| rounding.increment() <= 1) {
			return minutes;
		}
		int step = rounding.increment();
		Document remainder = new Document("$mod", List.of(
				new Document("$add", List.of(new Document("$mod", List.of(minutes, step)), step)), step));
		Document down = new Document("$subtract", List.of(minutes, remainder));
		Document up = new Document("$add", List.of(down, step));
		Document exact = new Document("$eq", List.of(remainder, 0));
		return switch (rounding.mode()) {
			case UP -> new Document("$cond", List.of(exact, minutes, up));
			case DOWN -> down;
			// Halves up, like TimeRounding: 7.5 minutes on a 15-minute step is 15.
			case NEAREST -> new Document("$cond", List.of(exact, minutes, new Document("$cond", List.of(
					new Document("$gte", List.of(new Document("$multiply", List.of(remainder, 2)), step)), up, down))));
			case NONE -> minutes;
		};
	}

	private static Document billableOf(String minutes) {
		return new Document("$cond", List.of(new Document("$eq", List.of("$billable", true)), minutes, 0));
	}

	/** The first day of a week bucket: the approval rhythm's, which the timesheet already follows. */
	DayOfWeek weekStart() {
		TimeTrackingSettings.ApprovalPeriod period = policy.approvalPeriod();
		return period == null || period.weekStartsOn() == null ? DayOfWeek.MONDAY : period.weekStartsOn();
	}

	// --- plumbing -----------------------------------------------------------

	private List<Document> aggregate(List<AggregationOperation> stages) {
		Aggregation aggregation = Aggregation.newAggregation(stages)
				.withOptions(AggregationOptions.builder().allowDiskUse(true).build());
		return mongo.aggregate(aggregation, WorkItem.class, Document.class).getMappedResults();
	}

	/** A stage written as it reads in the Mongo manual, past the fields Spring knows of. */
	private static AggregationOperation stage(String operator, Object value) {
		return context -> new Document(operator, value);
	}

	/** The named fields of the [type] documents with these ids, by id. */
	Map<String, Document> documents(Class<?> type, Collection<String> ids, String... fields) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Query query = Query.query(Criteria.where("_id").in(ids));
		for (String field : fields) {
			query.fields().include(field);
		}
		Map<String, Document> found = new HashMap<>();
		for (Document document : mongo.query(type).as(Document.class).matching(query).all()) {
			found.put(WorkItemDocuments.id(document), document);
		}
		return found;
	}

	private static Set<String> idsOf(List<WorkItem> items, java.util.function.Function<WorkItem, String> id) {
		return items.stream().map(id).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/** A group key as the string a client reads: an id, a name, or the ISO day a bucket starts. */
	private static String keyOf(Object value) {
		return switch (value) {
			case null -> null;
			case Date date -> LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC).toString();
			case ObjectId id -> id.toHexString();
			default -> value.toString();
		};
	}

	private static long number(Document row, String field) {
		return row.get(field) instanceof Number number ? number.longValue() : 0;
	}

	private static String firstNonBlank(String first, String second) {
		return first != null && !first.isBlank() ? first : second;
	}
}
