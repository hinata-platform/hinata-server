package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The report "absences and balances" (HIN-119): entitlement, carried in, taken, planned, left, and
 * what lapses when — per person or per type, for one leave year.
 *
 * <p><b>It is a policy, not a menu item.</b> Without {@code absenceReportsEnabled} it does not
 * exist (404). Who sees what is decided in the query, never by filtering a wider answer:
 *
 * <ul>
 * <li><b>A keeper</b> reads everybody, per person or per type, and — only with
 * {@code absenceRateEnabled} — the share of working time each person was away. That share is
 * conduct data and, with sickness in it, health data (R10), which is why it is its own switch and
 * why it is simply absent from the answer without it, not blanked by a screen.</li>
 * <li><b>A lead</b> reads sums per type over a group they plan, only while leads see their members'
 * entries anyway, only for groups of at least {@link TeamAbsenceService#BAND_MIN_PEOPLE}, and never a
 * type of the sick kind. A sum over two, one of them the reader, is the other person's figure.</li>
 * <li><b>Everybody else</b> reads their own figures.</li>
 * </ul>
 *
 * <p>Balances only: the types with a quota. Sick days have no balance to report; they appear
 * nowhere here except, for a keeper with the rate switched on, inside the share of time away.
 */
@Service
@RequiredArgsConstructor
public class TimeOffReportService {

	/** Longest page of rows. */
	public static final int PAGE_MAX = 100;

	/** Most people a group rate is read over; capacity reads no more at once. */
	static final int RATE_GROUP_MAX = 1_000;

	private static final Collation BY_NAME = Collation.of("en").strength(Collation.ComparisonLevel.secondary());

	private final TimeTrackingSettings policy;
	private final TimeOffAccess access;
	private final TimeOffTypeRepository types;
	private final TeamAbsenceService teams;
	private final TimeOffAbsences absences;
	private final UserRepository users;
	private final MongoTemplate mongo;
	private final Clock clock;

	/** Rows per person for one type, or one row per type summed over the people in scope. */
	public enum GroupBy {
		PERSON, TYPE
	}

	/** What a report asks: a leave year, a type, a group or some people, and how to group. */
	public record ReportQuery(Integer year, String typeId, String teamId, String projectId, List<String> userIds,
			GroupBy groupBy) {
	}

	/**
	 * One row's figures, in thousandths of a working day.
	 *
	 * @param ratePermille the share of planned working time away, in thousandths — present only for a
	 *                     keeper with {@code absenceRateEnabled}, and absent from the JSON otherwise
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Figures(int entitledMilliDays, int carriedInMilliDays, int takenMilliDays, int plannedMilliDays,
			int remainingMilliDays, int expiringMilliDays, LocalDate expiringOn, Integer ratePermille) {

		static final Figures ZERO = new Figures(0, 0, 0, 0, 0, 0, null, null);

		Figures plus(Figures other) {
			LocalDate on = expiringOn == null ? other.expiringOn
					: other.expiringOn == null || expiringOn.isBefore(other.expiringOn) ? expiringOn : other.expiringOn;
			return new Figures(entitledMilliDays + other.entitledMilliDays,
					carriedInMilliDays + other.carriedInMilliDays, takenMilliDays + other.takenMilliDays,
					plannedMilliDays + other.plannedMilliDays, remainingMilliDays + other.remainingMilliDays,
					expiringMilliDays + other.expiringMilliDays, on, null);
		}

		Figures withRate(Integer permille) {
			return new Figures(entitledMilliDays, carriedInMilliDays, takenMilliDays, plannedMilliDays,
					remainingMilliDays, expiringMilliDays, expiringOn, permille);
		}
	}

	/** A person (with their name) or a type, and its figures. */
	public record Row(String userId, String name, String typeId, Figures figures) {
	}

	/**
	 * The report: the head figures over everybody in scope, and a page of rows.
	 *
	 * @param people how many people the head figures cover
	 */
	public record Report(int year, GroupBy groupBy, String typeId, boolean rateVisible, int people, Figures totals,
			Page<Row> rows) {
	}

	/** Who the report is about, as the reader may see it. {@code userIds} null means everybody. */
	record Scope(boolean keeper, boolean lead, List<String> userIds, int year, GroupBy groupBy, TimeOffType type,
			List<TimeOffType> types, boolean rate) {
	}

	/** The report [viewer] asked for, page [page] of its rows. */
	public Report report(User viewer, ReportQuery query, int page, int size) {
		return build(scope(viewer, query), Math.max(0, page), Math.clamp(size, 1, PAGE_MAX));
	}

	/** The same report with up to [maxRows] rows on one page, for a file. */
	Report forExport(User viewer, ReportQuery query, int maxRows) {
		return build(scope(viewer, query), 0, maxRows);
	}

	private Report build(Scope scope, int pageNumber, int pageSize) {
		LocalDate today = LocalDate.now(clock);
		Map<String, Map<String, Figures>> byPerson = figures(scope, scope.userIds(), today);
		Figures totals = Figures.ZERO;
		for (Map<String, Figures> perType : byPerson.values()) {
			for (Figures figures : perType.values()) {
				totals = totals.plus(figures);
			}
		}
		Page<Row> rows = scope.groupBy() == GroupBy.PERSON
				? personRows(scope, byPerson, today, pageNumber, pageSize)
				: typeRows(scope, byPerson, pageNumber, pageSize);
		int people = scope.groupBy() == GroupBy.PERSON ? (int) rows.getTotalElements()
				: scope.userIds() == null ? byPerson.size() : scope.userIds().size();
		if (scope.rate()) {
			totals = totals.withRate(rateOver(scope, scope.userIds() == null
					? List.copyOf(byPerson.keySet()) : scope.userIds(), today));
		}
		return new Report(scope.year(), scope.groupBy(), scope.type() == null ? null : scope.type().getId(),
				scope.rate(), people, totals, rows);
	}

	// --- scope -----------------------------------------------------------------------

	Scope scope(User viewer, ReportQuery query) {
		if (!policy.absenceReportsEnabled()) {
			throw new ApiException(HttpStatus.NOT_FOUND, AbsenceManagementGate.DISABLED_KEY);
		}
		GroupBy groupBy = query.groupBy() == null ? GroupBy.PERSON : query.groupBy();
		boolean hasGroup = notBlank(query.teamId()) || notBlank(query.projectId());
		boolean keeper = access.isKeeper(viewer);
		List<String> ids;
		boolean lead = false;
		if (keeper) {
			if (query.userIds() != null && !query.userIds().isEmpty()) {
				ids = query.userIds().stream().filter(TimeOffReportService::notBlank).distinct().toList();
			}
			else {
				ids = hasGroup ? teams.reportGroup(viewer, query.teamId(), query.projectId()).userIds() : null;
			}
		}
		else if (hasGroup) {
			if (!policy.leadsSeeMemberEntries()) {
				throw ApiException.forbidden("error.availability.forbidden");
			}
			TeamAbsenceService.Members members = teams.reportGroup(viewer, query.teamId(), query.projectId());
			if (members.userIds().size() < TeamAbsenceService.BAND_MIN_PEOPLE) {
				throw ApiException.forbidden("error.timeOff.bandTooSmall", TeamAbsenceService.BAND_MIN_PEOPLE);
			}
			if (groupBy == GroupBy.PERSON) {
				throw ApiException.forbidden("error.timeOff.reportPeopleForbidden");
			}
			ids = members.userIds();
			lead = true;
		}
		else {
			boolean others = query.userIds() != null
					&& query.userIds().stream().anyMatch(id -> notBlank(id) && !id.equals(viewer.getId()));
			if (others) {
				throw ApiException.forbidden("error.timeOff.forbidden");
			}
			ids = List.of(viewer.getId());
		}
		boolean sums = lead;
		List<TimeOffType> balanceTypes = types.findAll().stream()
				.filter(type -> type.countsAgainstBalance() && !type.isUnlimited())
				// A lead never sees a type of the sick kind, even one an operator gave a quota.
				.filter(type -> !sums || type.getKind() != TimeOffType.Kind.SICK)
				.sorted(Comparator.comparing(type -> type.getName() == null ? "" : type.getName(),
						String.CASE_INSENSITIVE_ORDER))
				.toList();
		TimeOffType type = null;
		if (notBlank(query.typeId())) {
			type = balanceTypes.stream().filter(candidate -> candidate.getId().equals(query.typeId())).findFirst()
					.orElseThrow(() -> ApiException.notFound("timeOffType"));
		}
		else if (groupBy == GroupBy.PERSON) {
			type = balanceTypes.stream().filter(candidate -> TimeOffType.SYSTEM_VACATION.equals(candidate.getSystemKey()))
					.findFirst().orElse(balanceTypes.isEmpty() ? null : balanceTypes.getFirst());
		}
		List<TimeOffType> reported = type != null ? List.of(type) : balanceTypes;
		TimeOffType anchorType = type != null ? type : reported.isEmpty() ? null : reported.getFirst();
		LocalDate today = LocalDate.now(clock);
		int year = query.year() != null ? query.year()
				: anchorType == null ? today.getYear() : TimeOffBalances.leaveYearOf(today, anchorType.yearAnchor());
		if (Math.abs(year - today.getYear()) > TimeOffEntitlement.YEARS_AROUND_TODAY) {
			throw ApiException.badRequest("error.timeOff.yearOutOfRange", TimeOffEntitlement.YEARS_AROUND_TODAY);
		}
		return new Scope(keeper, lead, ids, year, groupBy, type, reported, keeper && policy.absenceRateEnabled());
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	// --- figures -----------------------------------------------------------------------

	/**
	 * Every person's figures per type for the scope, from two aggregations: the journal by kind, and
	 * the booked days still ahead of them. {@code userIds} null reads everybody with a booking.
	 */
	Map<String, Map<String, Figures>> figures(Scope scope, Collection<String> userIds, LocalDate today) {
		Map<String, TimeOffType> byId = new LinkedHashMap<>();
		scope.types().forEach(type -> byId.put(type.getId(), type));
		if (byId.isEmpty() || userIds != null && userIds.isEmpty()) {
			return Map.of();
		}
		Criteria match = Criteria.where("typeId").in(byId.keySet()).and("year").is(scope.year());
		if (userIds != null) {
			match = match.and("userId").in(userIds);
		}
		Map<String, Map<String, Map<TimeOffLedgerEntry.Kind, Integer>>> sums = new HashMap<>();
		for (Document row : mongo.aggregate(Aggregation.newAggregation(Aggregation.match(match),
						Aggregation.group("userId", "typeId", "kind").sum("milliDays").as("total")),
				TimeOffLedgerEntry.class, Document.class)) {
			Document id = row.get("_id", Document.class);
			String kind = id.getString("kind");
			if (id.getString("userId") == null || id.getString("typeId") == null || kind == null) {
				continue;
			}
			sums.computeIfAbsent(id.getString("userId"), key -> new HashMap<>())
					.computeIfAbsent(id.getString("typeId"), key -> new EnumMap<>(TimeOffLedgerEntry.Kind.class))
					.merge(TimeOffLedgerEntry.Kind.valueOf(kind), toInt(row.get("total")), Integer::sum);
		}
		Criteria ahead = Criteria.where("typeId").in(byId.keySet()).and("year").is(scope.year())
				.and("kind").in(TimeOffBalanceService.AHEAD_KINDS).and("effectiveOn").gt(today);
		if (userIds != null) {
			ahead = ahead.and("userId").in(userIds);
		}
		Map<String, Map<String, Integer>> planned = new HashMap<>();
		for (Document row : mongo.aggregate(Aggregation.newAggregation(Aggregation.match(ahead),
						Aggregation.group("userId", "typeId").sum("milliDays").as("total")),
				TimeOffLedgerEntry.class, Document.class)) {
			Document id = row.get("_id", Document.class);
			planned.computeIfAbsent(id.getString("userId"), key -> new HashMap<>())
					.put(id.getString("typeId"), -toInt(row.get("total")));
		}
		Map<String, Map<String, Figures>> figures = new LinkedHashMap<>();
		sums.forEach((userId, perType) -> perType.forEach((typeId, byKind) -> {
			TimeOffType type = byId.get(typeId);
			int stillAhead = planned.getOrDefault(userId, Map.of()).getOrDefault(typeId, 0);
			figures.computeIfAbsent(userId, key -> new LinkedHashMap<>())
					.put(typeId, figuresOf(type, scope.year(), byKind, stillAhead, today));
		}));
		return figures;
	}

	/** One person's figures for one type, the way their own balance screen adds them up. */
	static Figures figuresOf(TimeOffType type, int year, Map<TimeOffLedgerEntry.Kind, Integer> byKind, int planned,
			LocalDate today) {
		int entitled = byKind.getOrDefault(TimeOffLedgerEntry.Kind.ACCRUAL, 0)
				+ byKind.getOrDefault(TimeOffLedgerEntry.Kind.ADJUSTMENT, 0)
				+ byKind.getOrDefault(TimeOffLedgerEntry.Kind.CONVERSION, 0);
		int carriedIn = byKind.getOrDefault(TimeOffLedgerEntry.Kind.CARRYOVER_IN, 0);
		int booked = byKind.getOrDefault(TimeOffLedgerEntry.Kind.BOOKED, 0)
				+ byKind.getOrDefault(TimeOffLedgerEntry.Kind.RETURNED, 0);
		int remaining = 0;
		for (Integer value : byKind.values()) {
			remaining += value == null ? 0 : value;
		}
		TimeOffBalanceService.Expiring expiring = TimeOffBalanceService.Expiring.of(type, year, carriedIn, remaining,
				today);
		return new Figures(entitled, carriedIn, Math.max(0, -booked - planned), planned, remaining,
				expiring.milliDays(), expiring.on(), null);
	}

	// --- rows ------------------------------------------------------------------------

	/** A page of people for one type, by name, each with their figures and — for a keeper — their rate. */
	private Page<Row> personRows(Scope scope, Map<String, Map<String, Figures>> byPerson, LocalDate today, int page,
			int size) {
		PageRequest request = PageRequest.of(page, size);
		Page<User> people;
		if (scope.userIds() == null) {
			// Sorted like the team calendar, where case does not decide the order: "admin" sits among the As.
			Query active = Query.query(Criteria.where("active").is(true));
			long total = mongo.count(active, User.class);
			List<User> slice = mongo.find(active
					.with(PageRequest.of(page, size, Sort.by(Sort.Order.asc("displayName"), Sort.Order.asc("_id"))))
					.collation(BY_NAME), User.class);
			people = new PageImpl<>(slice, request, total);
		}
		else {
			List<User> found = new ArrayList<>(users.findAllById(scope.userIds()));
			found.sort(Comparator.comparing(person -> person.getDisplayName() == null ? "" : person.getDisplayName(),
					String.CASE_INSENSITIVE_ORDER));
			int from = Math.min(found.size(), page * size);
			people = new PageImpl<>(found.subList(from, Math.min(found.size(), from + size)), request, found.size());
		}
		String typeId = scope.type() == null ? null : scope.type().getId();
		Map<String, Integer> rates = scope.rate()
				? ratesOf(scope, people.getContent().stream().map(User::getId).toList(), today) : Map.of();
		return people.map(person -> {
			Figures figures = typeId == null ? Figures.ZERO
					: byPerson.getOrDefault(person.getId(), Map.of()).getOrDefault(typeId, Figures.ZERO);
			if (scope.rate()) {
				figures = figures.withRate(rates.get(person.getId()));
			}
			return new Row(person.getId(), person.getDisplayName(), typeId, figures);
		});
	}

	/** One row per type, summed over everybody in scope. */
	private Page<Row> typeRows(Scope scope, Map<String, Map<String, Figures>> byPerson, int page, int size) {
		Map<String, Figures> byType = new LinkedHashMap<>();
		scope.types().forEach(type -> byType.put(type.getId(), Figures.ZERO));
		for (Map<String, Figures> perType : byPerson.values()) {
			perType.forEach((typeId, figures) -> byType.merge(typeId, figures, Figures::plus));
		}
		List<Row> rows = new ArrayList<>();
		byType.forEach((typeId, figures) -> rows.add(new Row(null, null, typeId, figures)));
		int from = Math.min(rows.size(), page * size);
		return new PageImpl<>(rows.subList(from, Math.min(rows.size(), from + size)), PageRequest.of(page, size),
				rows.size());
	}

	// --- the rate ----------------------------------------------------------------------

	/** The window a rate is read over: the leave year so far. Null before it has begun. */
	private LocalDate[] rateWindow(Scope scope, LocalDate today) {
		TimeOffType anchor = scope.type() != null ? scope.type() : scope.types().isEmpty() ? null : scope.types().getFirst();
		java.time.MonthDay day = anchor == null ? java.time.MonthDay.of(1, 1) : anchor.yearAnchor();
		LocalDate from = TimeOffBalances.yearStart(scope.year(), day);
		LocalDate end = TimeOffBalances.yearEnd(scope.year(), day);
		LocalDate to = today.isBefore(end) ? today : end;
		return to.isBefore(from) ? null : new LocalDate[] {from, to};
	}

	private Map<String, Integer> ratesOf(Scope scope, List<String> userIds, LocalDate today) {
		LocalDate[] window = rateWindow(scope, today);
		if (window == null || userIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Integer> rates = new HashMap<>();
		absences.awayShares(userIds, window[0], window[1]).forEach((userId, share) -> {
			Integer permille = share.permille();
			if (permille != null) {
				rates.put(userId, permille);
			}
		});
		return rates;
	}

	/**
	 * The share of time away over a whole group, as the head figure. Null for a group larger than
	 * capacity reads at once: a rate over part of it would claim to be about all of it.
	 */
	private Integer rateOver(Scope scope, List<String> userIds, LocalDate today) {
		LocalDate[] window = rateWindow(scope, today);
		if (window == null || userIds.isEmpty()
				|| userIds.size() > RATE_GROUP_MAX) {
			return null;
		}
		long scheduled = 0;
		long holiday = 0;
		long away = 0;
		for (TimeOffAbsences.AwayShare share : absences.awayShares(userIds, window[0], window[1]).values()) {
			scheduled += share.scheduledMinutes();
			holiday += share.holidayMinutes();
			away += share.absenceMinutes();
		}
		long working = scheduled - holiday;
		return working <= 0 ? null : (int) Math.round(away * 1000.0 / working);
	}

	private static int toInt(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}
}
