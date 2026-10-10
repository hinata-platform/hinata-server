package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.timetracking.TimeRounding;
import com.ahmadre.hinata.timetracking.TimeWorkloadReport;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The money behind the time (HIN-96): the billing, profitability and utilization reports.
 *
 * <p>Every entry is valued on its own day with the rate in force that day ({@link RateResolver}),
 * after its minutes are rounded the way the reports round them (stage 6) — so a report and the
 * invoice written from the same entries agree. Revenue comes from billable entries only; cost from
 * every entry. Amounts are added up exactly in cent-minutes and rounded once per group.
 *
 * <p>Who reads what: an administrator everything, costs and margins included; a project lead the
 * revenue and utilization of the projects they lead, and per person only while the operator lets
 * leads see their members' entries. Profitability needs costs and is an administrator's alone.
 * There is no profitability per person, and no grouping that ranks people: people come in order
 * of name, like every people report here (R7, R10).
 *
 * <p>Rates do not live in the entries, so the valuation cannot be a Mongo pipeline. The window is
 * at most a year and the entries are streamed once with five fields each; only the groups are
 * held. A report wider than {@link #ENTRIES_MAX} entries is refused rather than answered slowly.
 */
@Service
@RequiredArgsConstructor
public class BillingReportService {

	/** Longest window, like every time report. */
	public static final int MAX_DAYS = 366;

	/** Largest page of groups. */
	public static final int PAGE_MAX = 100;

	/** Most entries one report values. */
	static final int ENTRIES_MAX = 2_000_000;

	/** Most groups one report holds. */
	static final int GROUPS_MAX = 10_000;

	/** Most people whose capacity one utilization total adds up. */
	static final int CAPACITY_PEOPLE_MAX = 5_000;

	private final MongoTemplate mongo;
	private final BillingAccess access;
	private final BillingSettings settings;
	private final BillingRateService rates;
	private final BillingNames names;
	private final TimeWorkloadReport workload;

	public enum Kind {
		BILLING, PROFITABILITY, UTILIZATION
	}

	public enum GroupBy {
		PROJECT, ISSUE, USER, TEAM, DAY, WEEK, MONTH;

		boolean isTime() {
			return this == DAY || this == WEEK || this == MONTH;
		}
	}

	/** A report as asked. */
	public record ReportQuery(Kind kind, LocalDate from, LocalDate to, List<String> projectIds, GroupBy groupBy) {
	}

	/**
	 * One group, or the totals. Fields a report does not compute are null.
	 *
	 * @param unratedBillableMinutes billable minutes no revenue rate covered — billed at nothing
	 * @param unratedMinutes         minutes no cost rate covered (profitability)
	 * @param billablePermille       billable share of the time (utilization)
	 * @param capacityMinutes        the group's capacity in the window (utilization per team,
	 *                               administrators)
	 */
	public record Row(String key, String label, String detail, long minutes, long billableMinutes,
			Long revenueCents, Long unratedBillableMinutes, Long costCents, Long unratedMinutes, Long marginCents,
			Integer marginPermille, Integer billablePermille, Long capacityMinutes, Integer capacityPermille) {
	}

	/** @param costs whether costs and margins are in it */
	public record Report(Kind kind, GroupBy groupBy, String currency, LocalDate from, LocalDate to, Row totals,
			Page<Row> groups, boolean costs) {
	}

	/** Running sums of one group. */
	private static final class Sums {
		long minutes;
		long billable;
		long revenueCentMinutes;
		long unratedBillable;
		long costCentMinutes;
		long unrated;
		final Set<String> people = new HashSet<>();
	}

	public Report report(User viewer, ReportQuery query, int page, int size) {
		BillingAccess.Reach reach = access.require(viewer);
		Kind kind = query.kind() == null ? Kind.BILLING : query.kind();
		GroupBy groupBy = query.groupBy() == null ? GroupBy.PROJECT : query.groupBy();
		if (kind == Kind.PROFITABILITY) {
			access.requireCosts(reach);
		}
		checkGrouping(kind, groupBy);
		if (groupBy == GroupBy.USER && !reach.admin() && !settings.leadsSeeMemberEntries()) {
			throw ApiException.forbidden("error.billing.report.people");
		}
		checkWindow(query.from(), query.to());
		Criteria criteria = scope(reach, query);
		boolean costs = kind == Kind.PROFITABILITY;
		RateResolver revenue = RateResolver.of(rates.all(BillingRate.Kind.BILLABLE));
		RateResolver cost = costs ? RateResolver.of(rates.all(BillingRate.Kind.COST)) : null;
		Map<String, List<String>> teams = names.teamsByPerson();

		Sums totals = new Sums();
		Map<String, Sums> groups = new LinkedHashMap<>();
		Query read = Query.query(criteria).cursorBatchSize(2_000);
		read.fields().include("userId", "projectId", "issueId", "date", "durationMinutes", "billable");
		long seen = 0;
		try (Stream<Document> entries = mongo.query(WorkItem.class).as(Document.class).matching(read).stream()) {
			for (Document entry : (Iterable<Document>) entries::iterator) {
				if (++seen > ENTRIES_MAX) {
					throw ApiException.badRequest("error.time.report.tooBroad");
				}
				LocalDate day = day(entry.get("date"));
				String userId = entry.getString("userId");
				String projectId = entry.getString("projectId");
				String issueId = entry.getString("issueId");
				List<String> teamIds = userId == null ? List.of() : teams.getOrDefault(userId, List.of());
				int minutes = TimeRounding.round(entry.get("durationMinutes") instanceof Number number
						? number.intValue() : 0, settings.rounding());
				boolean billable = Boolean.TRUE.equals(entry.get("billable"));
				RateResolver.Facts facts = new RateResolver.Facts(userId, projectId, issueId, day, teamIds);
				BillingRate revenueRate = billable ? revenue.resolve(facts) : null;
				BillingRate costRate = cost == null ? null : cost.resolve(facts);
				for (String key : keys(groupBy, userId, projectId, issueId, day, teamIds)) {
					Sums sums = groups.computeIfAbsent(key == null ? "" : key, k -> new Sums());
					if (groups.size() > GROUPS_MAX) {
						throw ApiException.badRequest("error.time.report.tooBroad");
					}
					add(sums, minutes, billable, revenueRate, costRate, costs, userId);
				}
				add(totals, minutes, billable, revenueRate, costRate, costs, userId);
			}
		}

		Pageable pageable = PageRequest.of(Math.clamp(page, 0, 10_000), Math.clamp(size, 1, PAGE_MAX));
		boolean capacity = kind == Kind.UTILIZATION && reach.admin();
		List<Row> rows = new ArrayList<>(groups.size());
		groups.forEach((key, sums) -> rows.add(row(key.isEmpty() ? null : key, null, null, sums, kind, null)));
		List<Row> ordered;
		if (groupBy == GroupBy.USER) {
			// People in order of name, never of figures (R10): every row is named first.
			ordered = sortByName(label(viewer, groupBy, rows));
		}
		else {
			rows.sort(order(kind, groupBy));
			ordered = rows;
		}
		int fromIndex = (int) Math.min(pageable.getOffset(), ordered.size());
		int toIndex = Math.min(fromIndex + pageable.getPageSize(), ordered.size());
		List<Row> pageRows = ordered.subList(fromIndex, toIndex);
		if (groupBy != GroupBy.USER) {
			pageRows = label(viewer, groupBy, pageRows);
		}
		if (capacity && groupBy == GroupBy.TEAM) {
			pageRows = withTeamCapacity(pageRows, teams, query.from(), query.to());
		}
		Long totalCapacity = capacity ? totalCapacity(totals, query.from(), query.to()) : null;
		Row total = row(null, null, null, totals, kind, totalCapacity);
		return new Report(kind, groupBy, settings.currency(), query.from(), query.to(), total,
				new PageImpl<>(pageRows, pageable, ordered.size()), costs);
	}

	// --- rules --------------------------------------------------------------

	private static void checkGrouping(Kind kind, GroupBy groupBy) {
		boolean allowed = switch (kind) {
			case BILLING -> true;
			// No margin per person: what one person is worth is not a report (R7).
			case PROFITABILITY -> groupBy == GroupBy.PROJECT || groupBy == GroupBy.ISSUE || groupBy == GroupBy.TEAM
					|| groupBy.isTime();
			// No utilization per person either: that is the workload report's, behind its own policy.
			case UTILIZATION -> groupBy == GroupBy.PROJECT || groupBy == GroupBy.TEAM || groupBy.isTime();
		};
		if (!allowed) {
			throw ApiException.badRequest("error.billing.report.grouping");
		}
	}

	static void checkWindow(LocalDate from, LocalDate to) {
		if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
			throw ApiException.badRequest("error.time.report.invalidRange");
		}
	}

	/** The entries the reader may value: everything for an administrator, the led projects for a lead. */
	private static Criteria scope(BillingAccess.Reach reach, ReportQuery query) {
		List<Criteria> all = new ArrayList<>();
		all.add(Criteria.where("date").gte(query.from()).lte(query.to()));
		List<String> asked = query.projectIds() == null ? List.of()
				: query.projectIds().stream().filter(Objects::nonNull).distinct().toList();
		if (asked.size() > 100) {
			throw ApiException.badRequest("error.time.report.tooManyValues", 100);
		}
		if (!reach.admin()) {
			for (String projectId : asked) {
				if (!reach.leads(projectId)) {
					throw ApiException.forbidden("error.billing.notLead");
				}
			}
			all.add(Criteria.where("projectId").in(asked.isEmpty() ? reach.ledProjects() : asked));
		}
		else if (!asked.isEmpty()) {
			all.add(Criteria.where("projectId").in(asked));
		}
		return new Criteria().andOperator(all);
	}

	private static List<String> keys(GroupBy groupBy, String userId, String projectId, String issueId,
			LocalDate day, List<String> teamIds) {
		return switch (groupBy) {
			case PROJECT -> java.util.Collections.singletonList(projectId);
			case ISSUE -> java.util.Collections.singletonList(issueId);
			case USER -> java.util.Collections.singletonList(userId);
			// Somebody in two teams counts in both, like the time report's teams; the totals
			// come from their own sums for that reason.
			case TEAM -> teamIds.isEmpty() ? java.util.Collections.singletonList(null) : teamIds;
			case DAY -> List.of(day.toString());
			case WEEK -> List.of(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString());
			case MONTH -> List.of(day.withDayOfMonth(1).toString());
		};
	}

	private static void add(Sums sums, int minutes, boolean billable, BillingRate revenueRate, BillingRate costRate,
			boolean costs, String userId) {
		sums.minutes += minutes;
		if (billable) {
			sums.billable += minutes;
			if (revenueRate == null) {
				sums.unratedBillable += minutes;
			}
			else {
				sums.revenueCentMinutes += Money.centMinutes(revenueRate.getAmountCents(), minutes);
			}
		}
		if (costs) {
			if (costRate == null) {
				sums.unrated += minutes;
			}
			else {
				sums.costCentMinutes += Money.centMinutes(costRate.getAmountCents(), minutes);
			}
		}
		if (userId != null && sums.people.size() <= CAPACITY_PEOPLE_MAX) {
			sums.people.add(userId);
		}
	}

	private static Row row(String key, String label, String detail, Sums sums, Kind kind, Long capacityMinutes) {
		Long revenue = kind == Kind.UTILIZATION ? null : Money.cents(sums.revenueCentMinutes);
		Long unratedBillable = kind == Kind.UTILIZATION ? null : sums.unratedBillable;
		Long cost = kind == Kind.PROFITABILITY ? Money.cents(sums.costCentMinutes) : null;
		Long unrated = kind == Kind.PROFITABILITY ? sums.unrated : null;
		Long margin = cost == null ? null : revenue - cost;
		Integer marginPermille = margin == null ? null : Money.permille(margin, revenue);
		Integer billablePermille = kind == Kind.UTILIZATION ? Money.permille(sums.billable, sums.minutes) : null;
		Integer capacityPermille = capacityMinutes == null ? null : Money.permille(sums.billable, capacityMinutes);
		return new Row(key, label, detail, sums.minutes, sums.billable, revenue, unratedBillable, cost, unrated,
				margin, marginPermille, billablePermille, capacityMinutes, capacityPermille);
	}

	/** Buckets by date; everything else by its main figure, largest first, then by key. */
	private static Comparator<Row> order(Kind kind, GroupBy groupBy) {
		if (groupBy.isTime()) {
			return Comparator.comparing(Row::key, Comparator.nullsLast(Comparator.naturalOrder()));
		}
		Comparator<Row> figure = switch (kind) {
			case BILLING, PROFITABILITY -> Comparator.comparing((Row row) -> row.revenueCents() == null ? 0L
					: row.revenueCents()).reversed();
			case UTILIZATION -> Comparator.comparingLong(Row::minutes).reversed();
		};
		return figure.thenComparing(Row::minutes, Comparator.reverseOrder())
				.thenComparing(Row::key, Comparator.nullsLast(Comparator.naturalOrder()));
	}

	private static List<Row> sortByName(List<Row> rows) {
		Collator collator = Collator.getInstance(Locale.ENGLISH);
		collator.setStrength(Collator.SECONDARY);
		List<Row> sorted = new ArrayList<>(rows);
		sorted.sort(Comparator.comparing((Row row) -> row.label() == null ? "￿" : row.label(), collator)
				.thenComparing(row -> Objects.toString(row.key(), "")));
		return sorted;
	}

	/** The rows with their names: one lookup per kind for the page. */
	private List<Row> label(User viewer, GroupBy groupBy, List<Row> rows) {
		List<String> keys = rows.stream().map(Row::key).filter(Objects::nonNull).toList();
		Map<String, BillingNames.Name> named;
		Set<String> readable = Set.of();
		switch (groupBy) {
			case PROJECT -> {
				named = names.projects(keys);
				readable = names.readable(viewer.getId(), keys);
			}
			case ISSUE -> {
				named = names.issues(keys);
				Map<String, String> projectOf = names.projectsOfIssues(keys);
				Set<String> readableProjects = names.readable(viewer.getId(), projectOf.values());
				Set<String> readableIssues = new HashSet<>();
				projectOf.forEach((issue, project) -> {
					if (readableProjects.contains(project)) {
						readableIssues.add(issue);
					}
				});
				readable = readableIssues;
			}
			case USER -> named = names.users(keys);
			case TEAM -> named = names.teams(keys);
			default -> named = Map.of();
		}
		List<Row> labelled = new ArrayList<>(rows.size());
		for (Row row : rows) {
			BillingNames.Name name = row.key() == null ? null : named.get(row.key());
			String label = null;
			String detail = null;
			if (name != null) {
				switch (groupBy) {
					// The key of a project and the readable id of an issue are what billing needs;
					// a project's name and an issue's title only where the reader is in the project.
					case PROJECT -> {
						label = readable.contains(row.key()) ? name.label() : null;
						detail = name.detail();
					}
					case ISSUE -> {
						label = readable.contains(row.key()) ? name.label() : null;
						detail = name.detail();
					}
					default -> {
						label = name.label();
						detail = name.detail();
					}
				}
			}
			labelled.add(new Row(row.key(), label, detail, row.minutes(), row.billableMinutes(), row.revenueCents(),
					row.unratedBillableMinutes(), row.costCents(), row.unratedMinutes(), row.marginCents(),
					row.marginPermille(), row.billablePermille(), row.capacityMinutes(), row.capacityPermille()));
		}
		return labelled;
	}

	/** Each team's capacity in the window, from its members' — never shown per person. */
	private List<Row> withTeamCapacity(List<Row> rows, Map<String, List<String>> teamsByPerson, LocalDate from,
			LocalDate to) {
		Map<String, List<String>> members = new HashMap<>();
		teamsByPerson.forEach((userId, teamIds) -> teamIds.forEach(teamId ->
				members.computeIfAbsent(teamId, id -> new ArrayList<>()).add(userId)));
		Set<String> people = new HashSet<>();
		for (Row row : rows) {
			if (row.key() != null) {
				people.addAll(members.getOrDefault(row.key(), List.of()));
			}
		}
		if (people.size() > CAPACITY_PEOPLE_MAX) {
			return rows;
		}
		Map<String, Integer> capacity = workload.capacityMinutes(people, from, to);
		List<Row> withCapacity = new ArrayList<>(rows.size());
		for (Row row : rows) {
			if (row.key() == null) {
				withCapacity.add(row);
				continue;
			}
			long minutes = 0;
			for (String userId : members.getOrDefault(row.key(), List.of())) {
				minutes += capacity.getOrDefault(userId, 0);
			}
			withCapacity.add(new Row(row.key(), row.label(), row.detail(), row.minutes(), row.billableMinutes(),
					row.revenueCents(), row.unratedBillableMinutes(), row.costCents(), row.unratedMinutes(),
					row.marginCents(), row.marginPermille(), row.billablePermille(), minutes,
					Money.permille(row.billableMinutes(), minutes)));
		}
		return withCapacity;
	}

	/** The capacity of everybody who booked time in the window; null when that is too many people. */
	private Long totalCapacity(Sums totals, LocalDate from, LocalDate to) {
		if (totals.people.isEmpty() || totals.people.size() > CAPACITY_PEOPLE_MAX) {
			return totals.people.isEmpty() ? 0L : null;
		}
		long minutes = 0;
		for (int value : workload.capacityMinutes(totals.people, from, to).values()) {
			minutes += value;
		}
		return minutes;
	}

	static LocalDate day(Object stored) {
		return switch (stored) {
			case LocalDate date -> date;
			case java.util.Date date -> LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC);
			case null -> null;
			default -> LocalDate.parse(stored.toString());
		};
	}
}
