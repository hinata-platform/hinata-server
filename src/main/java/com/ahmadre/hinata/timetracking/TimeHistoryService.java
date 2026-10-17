package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * How a budget has been used over time, how long it lasts at the present pace, and what a sprint
 * has used (HIN-156). Sums of a project or a sprint, never per person.
 */
@Service
@RequiredArgsConstructor
public class TimeHistoryService {

	/** The longest span one history answers. */
	public static final int HISTORY_MAX_DAYS = 366;

	/** The span the pace is read from. */
	static final int PACE_DAYS = 14;

	/** Fewer days with time than this in the span, and the forecast says it knows too little. */
	static final int PACE_MIN_DATA_DAYS = 3;

	/** How far ahead a forecast looks for the day a budget runs out. */
	static final int FORECAST_HORIZON_DAYS = 3 * 366;

	private final MongoTemplate mongo;
	private final TimeUsageService usage;
	private final Clock clock;
	private final TimeForecastHolidays forecastHolidays;

	/** One day with time recorded on it. */
	public record Day(LocalDate date, long minutes) {
	}

	/** Recorded minutes per day from [from] to [to], and the sum of everything before [from]. */
	public record History(String projectId, LocalDate from, LocalDate to, long before, List<Day> days) {
	}

	/** How sure a forecast is. */
	public enum Confidence { OK, LOW }

	/**
	 * At the pace of the last {@link #PACE_DAYS} days, per working day, when the budget runs out.
	 * {@code exhaustedOn} is null without a budget, when it is spent already, without pace, or past
	 * the horizon; {@code confidence} LOW with fewer than {@link #PACE_MIN_DATA_DAYS} days of data.
	 */
	public record Forecast(String projectId, long dailyMinutes, int dataDays, Long leftMinutes,
			LocalDate exhaustedOn, Confidence confidence) {
	}

	/** A sprint's figures. */
	public record SprintUsage(String sprintId, Integer budgetMinutes, long usedMinutes, int nearPercent,
			boolean canEdit) {

		SprintUsage editable(boolean may) {
			return new SprintUsage(sprintId, budgetMinutes, usedMinutes, nearPercent, may);
		}
	}

	public History history(String projectId, LocalDate from, LocalDate to) {
		List<Document> rows = mongo.aggregate(Aggregation.newAggregation(
						Aggregation.match(Criteria.where("projectId").is(projectId).and("date").gte(from).lte(to)),
						Aggregation.group("date").sum("durationMinutes").as("minutes"),
						Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "_id")),
				WorkItem.class, Document.class).getMappedResults();
		List<Day> days = new ArrayList<>(rows.size());
		long within = 0;
		for (Document row : rows) {
			long minutes = ((Number) row.get("minutes")).longValue();
			within += minutes;
			days.add(new Day(dayOf(row.get("_id")), minutes));
		}
		long total = usage.ofProjects(List.of(projectId)).getFirst().usedMinutes();
		// Everything up to [to] is the whole minus what lies after it; entries can be dated ahead.
		long after = sum(Criteria.where("projectId").is(projectId).and("date").gt(to));
		return new History(projectId, from, to, Math.max(0, total - after - within), days);
	}

	public Forecast forecast(String projectId) {
		TimeUsageService.ProjectUsage figures = usage.ofProjects(List.of(projectId)).getFirst();
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		LocalDate since = today.minusDays(PACE_DAYS - 1L);
		List<Document> rows = mongo.aggregate(Aggregation.newAggregation(
						Aggregation.match(Criteria.where("projectId").is(projectId).and("date").gte(since).lte(today)),
						Aggregation.group("date").sum("durationMinutes").as("minutes")),
				WorkItem.class, Document.class).getMappedResults();
		long recorded = rows.stream().mapToLong(row -> ((Number) row.get("minutes")).longValue()).sum();
		Set<LocalDate> holidays = forecastHolidays.between(since, today.plusDays(FORECAST_HORIZON_DAYS));
		int workdays = 0;
		for (LocalDate day = since; !day.isAfter(today); day = day.plusDays(1)) {
			if (workday(day, holidays)) workdays++;
		}
		long daily = workdays == 0 ? 0 : Math.round((double) recorded / workdays);
		Confidence confidence = rows.size() < PACE_MIN_DATA_DAYS ? Confidence.LOW : Confidence.OK;
		Long left = figures.budgetMinutes() == null ? null
				: Math.max(0, figures.budgetMinutes() - figures.usedMinutes());
		LocalDate exhausted = null;
		if (left != null && left > 0 && daily > 0 && confidence == Confidence.OK) {
			long remaining = left;
			LocalDate day = today;
			for (int i = 0; i < FORECAST_HORIZON_DAYS && remaining > 0; i++) {
				day = day.plusDays(1);
				if (workday(day, holidays)) remaining -= daily;
			}
			exhausted = remaining <= 0 ? day : null;
		}
		return new Forecast(projectId, daily, rows.size(), left, exhausted, confidence);
	}

	/** What a sprint has used: entries on its issues, within its dates. */
	public SprintUsage sprintUsage(Sprint sprint, List<String> boardProjectIds) {
		SprintTimeSettings own = mongo.findOne(Query.query(Criteria.where("sprintId").is(sprint.getId())),
				SprintTimeSettings.class);
		Integer budget = own == null || own.getBudgetMinutes() == null || own.getBudgetMinutes() <= 0
				? null : own.getBudgetMinutes();
		return new SprintUsage(sprint.getId(), budget, sprintMinutes(sprint, boardProjectIds),
				TimeAlerts.DEFAULT_BUDGET_PERCENT, false);
	}

	/** A budgeted sprint for the report, with its board's name and its figures. */
	public record BudgetedSprint(Sprint sprint, String boardName, SprintUsage usage) {
	}

	/**
	 * Active sprints with a budget on boards touching [visibleProjectIds], newest change first, at
	 * most [limit]. Reads the settings, then the sprints and boards in one query each.
	 */
	public List<BudgetedSprint> budgetedSprintsAmong(Set<String> visibleProjectIds, int limit) {
		List<SprintTimeSettings> budgeted = mongo.find(Query.query(Criteria.where("budgetMinutes").gt(0))
				.with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
						"updatedAt"))
				.limit(limit * 4), SprintTimeSettings.class);
		if (budgeted.isEmpty() || visibleProjectIds.isEmpty()) {
			return List.of();
		}
		java.util.Map<String, Sprint> sprints = new java.util.HashMap<>();
		mongo.find(Query.query(Criteria.where("_id").in(budgeted.stream().map(SprintTimeSettings::getSprintId).toList())
				.and("archived").ne(true)), Sprint.class).forEach(sprint -> sprints.put(sprint.getId(), sprint));
		java.util.Map<String, com.ahmadre.hinata.board.AgileBoard> boards = new java.util.HashMap<>();
		mongo.find(Query.query(Criteria.where("_id").in(sprints.values().stream().map(Sprint::getBoardId).distinct()
				.toList())), com.ahmadre.hinata.board.AgileBoard.class).forEach(board -> boards.put(board.getId(), board));
		List<BudgetedSprint> rows = new ArrayList<>();
		for (SprintTimeSettings own : budgeted) {
			if (rows.size() >= limit) break;
			Sprint sprint = sprints.get(own.getSprintId());
			com.ahmadre.hinata.board.AgileBoard board = sprint == null ? null : boards.get(sprint.getBoardId());
			if (board == null || board.getProjectIds().stream().noneMatch(visibleProjectIds::contains)) {
				continue;
			}
			rows.add(new BudgetedSprint(sprint, board.getName(),
					new SprintUsage(sprint.getId(), own.getBudgetMinutes(), sprintMinutes(sprint, board.getProjectIds()),
							TimeAlerts.DEFAULT_BUDGET_PERCENT, false)));
		}
		return rows;
	}

	/** An issue's sprint with a budget, and what it has used. */
	public record SprintFigures(Sprint sprint, long budgetMinutes, long usedMinutes) {
	}

	/**
	 * The budgeted sprint [issueId] belongs to, measured; empty when the issue is in no sprint or
	 * its sprint has no budget. Three small reads, and only the first for an issue in no sprint.
	 */
	public java.util.Optional<SprintFigures> sprintOfIssue(String issueId) {
		Query byId = Query.query(Criteria.where("_id").is(issueId));
		byId.fields().include("sprintId").include(Issue.PROJECTION_REQUIRED);
		Issue issue = mongo.findOne(byId, Issue.class);
		if (issue == null || issue.getSprintId() == null) {
			return java.util.Optional.empty();
		}
		SprintTimeSettings own = mongo.findOne(Query.query(Criteria.where("sprintId").is(issue.getSprintId())),
				SprintTimeSettings.class);
		if (own == null || own.getBudgetMinutes() == null || own.getBudgetMinutes() <= 0) {
			return java.util.Optional.empty();
		}
		Sprint sprint = mongo.findById(issue.getSprintId(), Sprint.class);
		if (sprint == null || sprint.isArchived()) {
			return java.util.Optional.empty();
		}
		com.ahmadre.hinata.board.AgileBoard board = mongo.findById(sprint.getBoardId(),
				com.ahmadre.hinata.board.AgileBoard.class);
		if (board == null) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new SprintFigures(sprint, own.getBudgetMinutes(),
				sprintMinutes(sprint, board.getProjectIds())));
	}

	long sprintMinutes(Sprint sprint, List<String> boardProjectIds) {
		Query issues = Query.query(Criteria.where("projectId").in(boardProjectIds).and("sprintId").is(sprint.getId()));
		issues.fields().include("_id").include(Issue.PROJECTION_REQUIRED);
		List<String> ids = mongo.find(issues, Issue.class).stream().map(Issue::getId).toList();
		if (ids.isEmpty()) {
			return 0;
		}
		Criteria entries = Criteria.where("issueId").in(ids);
		if (sprint.getStartDate() != null) {
			LocalDate end = sprint.getEndDate() != null ? sprint.getEndDate()
					: LocalDate.now(clock.withZone(ZoneOffset.UTC));
			entries = entries.and("date").gte(sprint.getStartDate()).lte(end);
		}
		return sum(entries);
	}

	private long sum(Criteria criteria) {
		Document row = mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria),
				Aggregation.group().sum("durationMinutes").as("minutes")), WorkItem.class, Document.class)
				.getUniqueMappedResult();
		return row == null ? 0 : ((Number) row.get("minutes")).longValue();
	}

	private static boolean workday(LocalDate day, Set<LocalDate> holidays) {
		return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
				&& !holidays.contains(day);
	}

	private static LocalDate dayOf(Object stored) {
		return switch (stored) {
			case LocalDate date -> date;
			case java.util.Date date -> LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC);
			default -> LocalDate.parse(stored.toString());
		};
	}

	/** Days between two dates, both counted. */
	static long span(LocalDate from, LocalDate to) {
		return ChronoUnit.DAYS.between(from, to) + 1;
	}
}
