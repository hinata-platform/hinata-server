package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.board.AgileBoard;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
		return forecasts(usage.ofProjects(List.of(projectId))).get(projectId);
	}

	/**
	 * {@link #forecast} for every row of [figures] at once: one aggregation for all their recent
	 * days and the holidays once, so a page of budgets costs two reads, not two per card.
	 */
	public Map<String, Forecast> forecasts(List<TimeUsageService.ProjectUsage> figures) {
		if (figures.isEmpty()) {
			return Map.of();
		}
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		LocalDate since = today.minusDays(PACE_DAYS - 1L);
		Map<String, Long> recorded = new HashMap<>();
		Map<String, Integer> dataDays = new HashMap<>();
		mongo.aggregate(Aggregation.newAggregation(
						Aggregation.match(Criteria.where("projectId").in(figures.stream()
								.map(TimeUsageService.ProjectUsage::projectId).toList()).and("date").gte(since).lte(today)),
						Aggregation.group("projectId", "date").sum("durationMinutes").as("minutes")),
				WorkItem.class, Document.class).getMappedResults().forEach(row -> {
					String projectId = row.get("_id", Document.class).getString("projectId");
					recorded.merge(projectId, ((Number) row.get("minutes")).longValue(), Long::sum);
					dataDays.merge(projectId, 1, Integer::sum);
				});
		Set<LocalDate> holidays = forecastHolidays.between(since, today.plusDays(FORECAST_HORIZON_DAYS));
		int workdays = 0;
		for (LocalDate day = since; !day.isAfter(today); day = day.plusDays(1)) {
			if (workday(day, holidays)) workdays++;
		}
		Map<String, Forecast> forecasts = new HashMap<>();
		for (TimeUsageService.ProjectUsage row : figures) {
			long sum = recorded.getOrDefault(row.projectId(), 0L);
			int days = dataDays.getOrDefault(row.projectId(), 0);
			long daily = workdays == 0 ? 0 : Math.round((double) sum / workdays);
			Confidence confidence = days < PACE_MIN_DATA_DAYS ? Confidence.LOW : Confidence.OK;
			Long left = row.budgetMinutes() == null ? null : Math.max(0, row.budgetMinutes() - row.usedMinutes());
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
			forecasts.put(row.projectId(), new Forecast(row.projectId(), daily, days, left, exhausted, confidence));
		}
		return forecasts;
	}

	/**
	 * A sprint has no threshold of its own: it warns at the instance's default share, wherever it is
	 * shown or heard (the entry's note, the board's capsule, the leads' alert).
	 */
	public static final int SPRINT_NEAR_PERCENT = TimeAlerts.DEFAULT_BUDGET_PERCENT;

	/**
	 * What a sprint has used: entries on its issues within its dates, counting only the issues of
	 * [countedProjectIds]. A board spans projects, and a reader of one of them must not learn from
	 * the sum what was booked in another they cannot see; the caller passes the board's projects the
	 * reader sees.
	 */
	public SprintUsage sprintUsage(Sprint sprint, Collection<String> countedProjectIds) {
		SprintTimeSettings own = mongo.findOne(Query.query(Criteria.where("sprintId").is(sprint.getId())),
				SprintTimeSettings.class);
		Integer budget = own == null || own.getBudgetMinutes() == null || own.getBudgetMinutes() <= 0
				? null : own.getBudgetMinutes();
		long used = sprintMinutes(Map.of(sprint.getId(), sprint), Map.of(sprint.getId(), Set.copyOf(countedProjectIds)))
				.getOrDefault(sprint.getId(), 0L);
		return new SprintUsage(sprint.getId(), budget, used, SPRINT_NEAR_PERCENT, false);
	}

	/** A budgeted sprint for the report, with its board's name and its figures. */
	public record BudgetedSprint(Sprint sprint, String boardName, SprintUsage usage) {
	}

	/**
	 * Running sprints with a budget on boards touching [visibleProjectIds], newest change first, at
	 * most [limit], each summed over the board's projects the reader sees. From the reader's
	 * boards down, so a reader whose sprints are older than everybody else's still finds them, and
	 * in a fixed number of reads however many sprints there are.
	 */
	public List<BudgetedSprint> budgetedSprintsAmong(Set<String> visibleProjectIds, int limit) {
		if (visibleProjectIds.isEmpty()) {
			return List.of();
		}
		Query boardQuery = Query.query(Criteria.where("projectIds").in(visibleProjectIds));
		boardQuery.fields().include("_id", "name", "projectIds");
		Map<String, AgileBoard> boards = new HashMap<>();
		mongo.find(boardQuery, AgileBoard.class).forEach(board -> boards.put(board.getId(), board));
		if (boards.isEmpty()) {
			return List.of();
		}
		Map<String, Sprint> sprints = new HashMap<>();
		mongo.find(Query.query(Criteria.where("boardId").in(boards.keySet()).and("archived").ne(true)
				.and("startDate").ne(null)), Sprint.class).forEach(sprint -> sprints.put(sprint.getId(), sprint));
		if (sprints.isEmpty()) {
			return List.of();
		}
		List<SprintTimeSettings> budgeted = mongo.find(Query.query(Criteria.where("sprintId").in(sprints.keySet())
				.and("budgetMinutes").gt(0))
				.with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
						"updatedAt"))
				.limit(limit), SprintTimeSettings.class);
		Map<String, Sprint> shown = new LinkedHashMap<>();
		Map<String, Set<String>> counted = new HashMap<>();
		for (SprintTimeSettings own : budgeted) {
			Sprint sprint = sprints.get(own.getSprintId());
			shown.put(sprint.getId(), sprint);
			counted.put(sprint.getId(), visibleOf(boards.get(sprint.getBoardId()), visibleProjectIds));
		}
		Map<String, Long> minutes = sprintMinutes(shown, counted);
		List<BudgetedSprint> rows = new ArrayList<>();
		for (SprintTimeSettings own : budgeted) {
			Sprint sprint = shown.get(own.getSprintId());
			rows.add(new BudgetedSprint(sprint, boards.get(sprint.getBoardId()).getName(),
					new SprintUsage(sprint.getId(), own.getBudgetMinutes(), minutes.getOrDefault(sprint.getId(), 0L),
							SPRINT_NEAR_PERCENT, false)));
		}
		return rows;
	}

	private static Set<String> visibleOf(AgileBoard board, Set<String> visibleProjectIds) {
		Set<String> counted = new java.util.HashSet<>(board.getProjectIds());
		counted.retainAll(visibleProjectIds);
		return counted;
	}

	/**
	 * An issue's sprint with a budget, and what it has used. [boardProjectIds] are all the board's
	 * projects, for a caller that has to know who may hear the figure.
	 */
	public record SprintFigures(Sprint sprint, List<String> boardProjectIds, long budgetMinutes, long usedMinutes) {
	}

	/**
	 * The sprint [sprintId] measured against its budget over the board's projects [counts]
	 * accepts; empty without a budget, or when the sprint is archived or gone.
	 */
	public java.util.Optional<SprintFigures> sprintFigures(String sprintId, java.util.function.Predicate<String> counts) {
		SprintTimeSettings own = mongo.findOne(Query.query(Criteria.where("sprintId").is(sprintId)),
				SprintTimeSettings.class);
		if (own == null || own.getBudgetMinutes() == null || own.getBudgetMinutes() <= 0) {
			return java.util.Optional.empty();
		}
		return sprintFigures(own, counts);
	}

	private java.util.Optional<SprintFigures> sprintFigures(SprintTimeSettings own,
			java.util.function.Predicate<String> counts) {
		Sprint sprint = mongo.findById(own.getSprintId(), Sprint.class);
		if (sprint == null || sprint.isArchived()) {
			return java.util.Optional.empty();
		}
		AgileBoard board = mongo.findById(sprint.getBoardId(), AgileBoard.class);
		if (board == null) {
			return java.util.Optional.empty();
		}
		Set<String> counted = board.getProjectIds().stream().filter(counts).collect(java.util.stream.Collectors.toSet());
		long used = sprintMinutes(Map.of(sprint.getId(), sprint), Map.of(sprint.getId(), counted))
				.getOrDefault(sprint.getId(), 0L);
		return java.util.Optional.of(new SprintFigures(sprint, board.getProjectIds(), own.getBudgetMinutes(), used));
	}

	/**
	 * Minutes per sprint of [sprints]: entries on the sprint's issues of the projects [counted] names
	 * for it, within the sprint's dates. Two reads for any number of sprints: their issues, then the
	 * entries grouped by issue and day, split per sprint here.
	 */
	Map<String, Long> sprintMinutes(Map<String, Sprint> sprints, Map<String, Set<String>> counted) {
		Set<String> projectIds = new java.util.HashSet<>();
		counted.values().forEach(projectIds::addAll);
		if (sprints.isEmpty() || projectIds.isEmpty()) {
			return Map.of();
		}
		Query issues = Query.query(Criteria.where("sprintId").in(sprints.keySet()).and("projectId").in(projectIds));
		issues.fields().include("_id", "sprintId", "projectId").include(Issue.PROJECTION_REQUIRED);
		Map<String, String> sprintOfIssue = new HashMap<>();
		mongo.find(issues, Issue.class).forEach(issue -> {
			if (counted.getOrDefault(issue.getSprintId(), Set.of()).contains(issue.getProjectId())) {
				sprintOfIssue.put(issue.getId(), issue.getSprintId());
			}
		});
		if (sprintOfIssue.isEmpty()) {
			return Map.of();
		}
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		Criteria entries = Criteria.where("issueId").in(sprintOfIssue.keySet());
		LocalDate earliest = sprints.values().stream().map(Sprint::getStartDate)
				.filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
		boolean unbounded = sprints.values().stream().anyMatch(sprint -> sprint.getStartDate() == null);
		if (earliest != null && !unbounded) {
			entries = entries.and("date").gte(earliest);
		}
		Map<String, Long> minutes = new HashMap<>();
		mongo.aggregate(Aggregation.newAggregation(Aggregation.match(entries),
				Aggregation.group("issueId", "date").sum("durationMinutes").as("minutes")),
				WorkItem.class, Document.class).getMappedResults().forEach(row -> {
					Document id = row.get("_id", Document.class);
					Sprint sprint = sprints.get(sprintOfIssue.get(id.getString("issueId")));
					if (sprint == null || !within(sprint, dayOf(id.get("date")), today)) {
						return;
					}
					minutes.merge(sprint.getId(), ((Number) row.get("minutes")).longValue(), Long::sum);
				});
		return minutes;
	}

	private static boolean within(Sprint sprint, LocalDate day, LocalDate today) {
		if (sprint.getStartDate() == null) {
			return true;
		}
		LocalDate end = sprint.getEndDate() != null ? sprint.getEndDate() : today;
		return !day.isBefore(sprint.getStartDate()) && !day.isAfter(end);
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
