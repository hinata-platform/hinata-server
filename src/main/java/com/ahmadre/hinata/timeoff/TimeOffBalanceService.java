package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Entitlements, balances and the bookings that move them — plus the two employment dates the
 * arithmetic reads, which live here because they are an input to it and to nothing else.
 *
 * <p>Named for balances rather than for the module because {@code availability.TimeOffService}
 * already exists and owns something different: the absences themselves, the documents capacity
 * reads. One name for two services would be one bean name for two beans, and the application
 * would not start.
 *
 * <p><b>A balance is never stored.</b> It is the sum of {@code time_off_ledger} for one person, one
 * type and one leave year, read on demand. Two keepers booking at the same moment cannot lose one
 * another's amount, because neither reads a total to write it back —
 * {@code reference_derived_counter_lost_update} is the bug this shape does not have.
 *
 * <p><b>Nothing is edited.</b> A wrong figure is corrected by booking its opposite with a reason.
 * That is not bookkeeping ceremony: what somebody was granted and when is the evidence an employer
 * needs for § 2 Abs. 1 S. 2 Nr. 8 NachwG, and a record that quietly rewrote itself could not
 * evidence anything.
 *
 * <p><b>Who sees what</b> is {@link TimeOffAccess}: one's own always, a keeper's anybody's, and a
 * lead nobody's. How many days somebody has left is not a question project planning has to answer
 * (R2, R10).
 */
@Service
@RequiredArgsConstructor
public class TimeOffBalanceService {

	/** Most people one bulk grant may touch in a single request. */
	public static final int BULK_MAX = 500;

	/** Longest page of ledger rows. */
	public static final int PAGE_MAX = 100;

	/** The kinds that make up what is still ahead: booked days, net of those given back. */
	static final List<String> AHEAD_KINDS = List.of(TimeOffLedgerEntry.Kind.BOOKED.name(),
			TimeOffLedgerEntry.Kind.RETURNED.name());

	private static final Sort OLDEST_FIRST =
			Sort.by(Sort.Order.asc("effectiveOn"), Sort.Order.asc("_id"));

	private final TimeOffTypeService types;
	private final TimeOffEntitlementRepository entitlements;
	private final TimeOffLedgerRepository ledger;
	private final TimeOffEmploymentRepository employment;
	private final TimeOffWorkWeek workWeek;
	private final TimeOffAccess access;
	private final UserRepository users;
	private final NotificationService notifications;
	private final AuditService audit;
	private final MongoTemplate mongo;
	private final Clock clock;

	/**
	 * One person's standing for one type and leave year.
	 *
	 * <p>{@code taken} and {@code planned} split the booked days at today rather than at the day
	 * somebody returns: toggl deducts on return, which reads oddly on the screen of somebody about
	 * to leave for three weeks — their balance still says they have the days. Splitting at today
	 * says both things at once: this is gone, that is committed.
	 */
	public record Balance(String typeId, int year, int entitledMilliDays, int accruedMilliDays,
			int carriedInMilliDays, int adjustedMilliDays, int takenMilliDays, int plannedMilliDays,
			int expiredMilliDays, int paidOutMilliDays, int remainingMilliDays, LocalDate expiresOn,
			boolean granted, boolean unlimited, TimeOffBalances.Reason reason,
			boolean belowLegalMinimum, int legalMinimumMilliDays, int expiringMilliDays,
			LocalDate expiringOn) {
	}

	/**
	 * What of a balance lapses next, and on which day, if nothing is taken before then (HIN-119).
	 *
	 * <p>Days carried in go first — they are the ones with the nearer deadline, and the days taken
	 * this year are counted against them first. Then, for a type that carries nothing or only part,
	 * what the year's end would take. This is the figure the person's card says and the notice
	 * repeats; whether the days actually lapse is the yearly run's to decide, and it lets nothing
	 * lapse without a notice behind it.
	 */
	record Expiring(int milliDays, LocalDate on) {

		static final Expiring NONE = new Expiring(0, null);

		static Expiring of(TimeOffType type, int year, int carriedIn, int remaining, LocalDate today) {
			if (type.isUnlimited() || !type.countsAgainstBalance() || remaining <= 0) {
				return NONE;
			}
			int carriedUnused = Math.min(carriedIn, remaining);
			LocalDate carriedDeadline = TimeOffBalances.carryoverDeadline(type.carryoverExpiresOn(), year - 1,
					type.yearAnchor());
			if (carriedUnused > 0 && !today.isAfter(carriedDeadline)) {
				return new Expiring(carriedUnused, carriedDeadline);
			}
			LocalDate yearEnd = TimeOffBalances.yearEnd(year, type.yearAnchor());
			if (today.isAfter(yearEnd)) {
				return NONE;
			}
			return switch (type.carryover()) {
				case NONE -> new Expiring(remaining, yearEnd);
				case CAPPED -> {
					int cap = type.getCarryoverCapMilliDays() == null ? 0 : type.getCarryoverCapMilliDays();
					yield remaining > cap ? new Expiring(remaining - cap, yearEnd) : NONE;
				}
				case UNLIMITED -> NONE;
			};
		}
	}

	/** What a bulk grant would do, before it does it. */
	public record GrantPreview(String userId, int accruedMilliDays, TimeOffBalances.Reason reason,
			boolean alreadyGranted) {
	}

	/**
	 * Where one person stands for one type and year, as a keeper's list shows it.
	 *
	 * <p>The joining and leaving dates travel with the row because they are the answer to the
	 * question the numbers raise: a keeper looking at eight and a third days wants to see July
	 * beside it, not to open a second screen to find out why.
	 */
	public record Standing(String userId, int entitledMilliDays, int accruedMilliDays,
			int adjustedMilliDays, int takenMilliDays, int plannedMilliDays, int remainingMilliDays,
			boolean granted, LocalDate hiredOn, LocalDate leftOn, int carriedInMilliDays) {
	}

	// --- balances --------------------------------------------------------------

	/**
	 * Every type's standing for [userId] in [year]: the types on offer, plus any retired one the
	 * person still has history under — a balance that vanished because an operator retired the
	 * type would be a balance somebody is still owed.
	 */
	public List<Balance> balances(User viewer, String userId, int year) {
		User person = access.requireSubject(viewer, userId);
		assertYearInRange(year);
		// One reading of the working week for the whole screen: the statutory floor is a property
		// of the person's week (§ 3 Abs. 1 BUrlG), not of each type, and asking per row would make
		// a balance screen cost as many round trips as the operator has types.
		int workingDays = workingDaysPerWeek(person.getId());
		Map<String, TimeOffType> byId = new LinkedHashMap<>();
		for (TimeOffType type : types.list(viewer, true)) {
			byId.put(type.getId(), type);
		}
		Map<String, Map<TimeOffLedgerEntry.Kind, Integer>> sums = sumsByType(person.getId(), year);
		Map<String, Integer> planned = plannedByType(person.getId(), year);
		Map<String, TimeOffEntitlement> granted = new HashMap<>();
		for (TimeOffEntitlement entitlement : entitlements.findByUserIdAndYear(person.getId(), year)) {
			granted.put(entitlement.getTypeId(), entitlement);
		}
		// A retired type with history still has to answer for itself — a balance that vanished
		// because an operator retired the type would be a balance somebody is still owed. In one
		// query: a member sees only active types, so every year of history under a retired one
		// would otherwise be its own round trip.
		List<String> retired = sums.keySet().stream().filter(id -> !byId.containsKey(id)).toList();
		if (!retired.isEmpty()) {
			for (TimeOffType type : types.findAllById(retired)) {
				byId.put(type.getId(), type);
			}
		}
		List<Balance> balances = new ArrayList<>();
		for (TimeOffType type : byId.values()) {
			if (!type.isActive() && !sums.containsKey(type.getId())
					&& !granted.containsKey(type.getId())) {
				continue;
			}
			balances.add(balance(type, year, sums.get(type.getId()),
					planned.getOrDefault(type.getId(), 0), granted.get(type.getId()), workingDays));
		}
		return balances;
	}

	private Balance balance(TimeOffType type, int year,
			Map<TimeOffLedgerEntry.Kind, Integer> sums, int planned, TimeOffEntitlement granted,
			int workingDaysPerWeek) {
		Map<TimeOffLedgerEntry.Kind, Integer> byKind =
				sums == null ? Map.of() : sums;
		int accrual = byKind.getOrDefault(TimeOffLedgerEntry.Kind.ACCRUAL, 0);
		int carriedIn = byKind.getOrDefault(TimeOffLedgerEntry.Kind.CARRYOVER_IN, 0);
		int carriedOut = byKind.getOrDefault(TimeOffLedgerEntry.Kind.CARRYOVER_OUT, 0);
		int adjusted = byKind.getOrDefault(TimeOffLedgerEntry.Kind.ADJUSTMENT, 0)
				+ byKind.getOrDefault(TimeOffLedgerEntry.Kind.CONVERSION, 0);
		int booked = byKind.getOrDefault(TimeOffLedgerEntry.Kind.BOOKED, 0)
				+ byKind.getOrDefault(TimeOffLedgerEntry.Kind.RETURNED, 0);
		int expired = byKind.getOrDefault(TimeOffLedgerEntry.Kind.EXPIRED, 0);
		int paidOut = byKind.getOrDefault(TimeOffLedgerEntry.Kind.PAYOUT, 0);
		// Bookings are negative; what is "taken" is the part that is already behind the person.
		int takenTotal = -booked;
		int remaining = accrual + carriedIn + carriedOut + adjusted + booked + expired + paidOut;
		Expiring expiring = Expiring.of(type, year, carriedIn, remaining, LocalDate.now(clock));
		return new Balance(type.getId(), year,
				granted == null ? 0 : granted.allowanceMilliDays(),
				accrual, carriedIn, adjusted,
				Math.max(0, takenTotal - planned), planned, expired, paidOut, remaining,
				carryoverExpiry(type, year), granted != null, type.isUnlimited(),
				granted == null ? null : reasonOf(granted),
				TimeOffLegalFloor.fallsShort(type, workingDaysPerWeek),
				TimeOffLegalFloor.minimumMilliDays(workingDaysPerWeek),
				expiring.milliDays(), expiring.on());
	}

	/**
	 * Why a year worked out the way it did: what was decided when it was granted.
	 *
	 * <p>Read, not re-derived. "Eight of twenty" is the same number whether somebody joined in
	 * July, left in March or is still inside their waiting period, and § 5 BUrlG treats those as
	 * three different things — a derivation from the numbers can only ever guess one of them. The
	 * guess is kept for documents written before the field existed, and for nothing else.
	 */
	private static TimeOffBalances.Reason reasonOf(TimeOffEntitlement granted) {
		if (granted.getReason() != null) {
			return granted.getReason();
		}
		if (granted.accruedMilliDays() == 0) {
			return TimeOffBalances.Reason.NOT_EMPLOYED;
		}
		return granted.accruedMilliDays() < granted.allowanceMilliDays()
				? TimeOffBalances.Reason.WAITING_PERIOD : TimeOffBalances.Reason.FULL;
	}

	/** When what was carried into [year] runs out, for a type that carries anything at all. */
	private static LocalDate carryoverExpiry(TimeOffType type, int year) {
		if (type.carryover() == TimeOffType.Carryover.NONE) {
			return null;
		}
		return TimeOffBalances.carryoverDeadline(type.carryoverExpiresOn(), year - 1, type.yearAnchor());
	}

	/** Every kind's total for a person and year, in one indexed aggregation. */
	private Map<String, Map<TimeOffLedgerEntry.Kind, Integer>> sumsByType(String userId, int year) {
		AggregationResults<Document> results = mongo.aggregate(
				Aggregation.newAggregation(
						Aggregation.match(Criteria.where("userId").is(userId).and("year").is(year)),
						Aggregation.group("typeId", "kind").sum("milliDays").as("total")),
				TimeOffLedgerEntry.class, Document.class);
		Map<String, Map<TimeOffLedgerEntry.Kind, Integer>> sums = new LinkedHashMap<>();
		for (Document row : results) {
			Document id = row.get("_id", Document.class);
			String typeId = id.getString("typeId");
			String kind = id.getString("kind");
			if (typeId == null || kind == null) {
				continue;
			}
			sums.computeIfAbsent(typeId, key -> new EnumMap<>(TimeOffLedgerEntry.Kind.class))
					.merge(TimeOffLedgerEntry.Kind.valueOf(kind), toInt(row.get("total")), Integer::sum);
		}
		return sums;
	}

	/**
	 * What is booked but still ahead of the person: days committed, not days gone. Net of what was
	 * given back: a cancelled absence books its days back on the day it would have started, so a
	 * cancellation next month is not still "planned" (HIN-119).
	 */
	private Map<String, Integer> plannedByType(String userId, int year) {
		AggregationResults<Document> results = mongo.aggregate(
				Aggregation.newAggregation(
						Aggregation.match(Criteria.where("userId").is(userId).and("year").is(year)
								.and("kind").in(AHEAD_KINDS)
								.and("effectiveOn").gt(LocalDate.now(clock))),
						Aggregation.group("typeId").sum("milliDays").as("total")),
				TimeOffLedgerEntry.class, Document.class);
		Map<String, Integer> planned = new LinkedHashMap<>();
		for (Document row : results) {
			planned.put(row.getString("_id"), -toInt(row.get("total")));
		}
		return planned;
	}

	private static int toInt(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	/** One person's movements for one type and year, oldest first — the order a ledger reads in. */
	public Page<TimeOffLedgerEntry> ledger(User viewer, String userId, String typeId, int year,
			int page, int size) {
		User person = access.requireSubject(viewer, userId);
		assertYearInRange(year);
		return ledger.findByUserIdAndTypeIdAndYear(person.getId(), typeId, year,
				PageRequest.of(Math.max(0, page), Math.clamp(size, 1, PAGE_MAX), OLDEST_FIRST));
	}

	/**
	 * A page of the directory beside where each person stands for one type and year — the list a
	 * keeper grants, corrects and reads journals from.
	 *
	 * <p>Four queries for the whole page, whatever its length: the people, their ledger sums,
	 * what of that is still ahead of them, and their grants. A row per request would be a screen
	 * of twenty-five names costing twenty-five round trips, and it is the shape that quietly
	 * stops working at two hundred employees.
	 *
	 * <p>Only active accounts, and only a keeper's to ask (R10): how many days somebody has left
	 * is not a question project planning has to answer.
	 */
	public Page<Standing> standings(User actor, String typeId, int year, String query, int page,
			int size) {
		access.requireKeeper(actor);
		assertYearInRange(year);
		TimeOffType type = types.require(typeId);
		Pageable pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, PAGE_MAX),
				Sort.by(Sort.Direction.ASC, "displayName"));
		String term = query == null ? "" : query.strip();
		// With no term this is the whole directory, and the search finder would read every active
		// document to run three unanchored regexes over it. The first page of a keeper's list is
		// exactly that case, so it asks the indexed finder instead.
		// With a term: escaped, like every other directory search — a term is a term, never a
		// pattern.
		Page<User> people = term.isEmpty() ? users.findByActiveIsTrue(pageable)
				: users.searchActive(Pattern.quote(term), pageable);
		List<String> ids = people.getContent().stream().map(User::getId).toList();
		if (ids.isEmpty()) {
			return people.map(person -> new Standing(person.getId(), 0, 0, 0, 0, 0, 0, false, null, null, 0));
		}
		Map<String, Map<TimeOffLedgerEntry.Kind, Integer>> sums = sumsByUser(ids, typeId, year);
		Map<String, Integer> planned = plannedByUser(ids, typeId, year);
		Map<String, TimeOffEntitlement> granted = new HashMap<>();
		for (TimeOffEntitlement entitlement :
				entitlements.findByTypeIdAndYearAndUserIdIn(typeId, year, ids)) {
			granted.put(entitlement.getUserId(), entitlement);
		}
		Map<String, TimeOffEmployment> facts = new HashMap<>();
		for (TimeOffEmployment row : employment.findByUserIdIn(ids)) {
			facts.put(row.getUserId(), row);
		}
		return people.map(person -> {
			// The floor plays no part in a keeper's list — it is about one person's own week, and
			// the list spans a page of them — so the standard week stands in and is not read.
			Balance balance = balance(type, year, sums.get(person.getId()),
					planned.getOrDefault(person.getId(), 0), granted.get(person.getId()),
					TimeOffLegalFloor.STANDARD_WORKING_DAYS);
			TimeOffEmployment dates = facts.get(person.getId());
			return new Standing(person.getId(), balance.entitledMilliDays(),
					balance.accruedMilliDays(), balance.adjustedMilliDays(), balance.takenMilliDays(),
					balance.plannedMilliDays(), balance.remainingMilliDays(), balance.granted(),
					dates == null ? null : dates.getHiredOn(),
					dates == null ? null : dates.getLeftOn(), balance.carriedInMilliDays());
		});
	}

	/** Every kind's total for one type and year, for a page of people, in one aggregation. */
	private Map<String, Map<TimeOffLedgerEntry.Kind, Integer>> sumsByUser(List<String> userIds,
			String typeId, int year) {
		AggregationResults<Document> results = mongo.aggregate(
				Aggregation.newAggregation(
						Aggregation.match(Criteria.where("userId").in(userIds).and("typeId").is(typeId)
								.and("year").is(year)),
						Aggregation.group("userId", "kind").sum("milliDays").as("total")),
				TimeOffLedgerEntry.class, Document.class);
		Map<String, Map<TimeOffLedgerEntry.Kind, Integer>> sums = new LinkedHashMap<>();
		for (Document row : results) {
			Document id = row.get("_id", Document.class);
			String userId = id.getString("userId");
			String kind = id.getString("kind");
			if (userId == null || kind == null) {
				continue;
			}
			sums.computeIfAbsent(userId, key -> new EnumMap<>(TimeOffLedgerEntry.Kind.class))
					.merge(TimeOffLedgerEntry.Kind.valueOf(kind), toInt(row.get("total")), Integer::sum);
		}
		return sums;
	}

	/** What is booked but still ahead of each of them, for the same page. */
	private Map<String, Integer> plannedByUser(List<String> userIds, String typeId, int year) {
		AggregationResults<Document> results = mongo.aggregate(
				Aggregation.newAggregation(
						Aggregation.match(Criteria.where("userId").in(userIds).and("typeId").is(typeId)
								.and("year").is(year).and("kind").in(AHEAD_KINDS)
								.and("effectiveOn").gt(LocalDate.now(clock))),
						Aggregation.group("userId").sum("milliDays").as("total")),
				TimeOffLedgerEntry.class, Document.class);
		Map<String, Integer> planned = new LinkedHashMap<>();
		for (Document row : results) {
			planned.put(row.getString("_id"), -toInt(row.get("total")));
		}
		return planned;
	}

	// --- granting a year ---------------------------------------------------------

	/**
	 * Grants [year] of [typeId] to one person: an entitlement row and the accrual that follows from
	 * it, in that order. Already granted is a refusal, not a second helping.
	 */
	public TimeOffEntitlement grant(User actor, String userId, String typeId, int year,
			Integer allowanceOverride, String note) {
		access.requireKeeper(actor);
		assertYearInRange(year);
		User person = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
		TimeOffType type = grantableType(typeId);
		if (entitlements.findByUserIdAndTypeIdAndYear(person.getId(), typeId, year).isPresent()) {
			throw ApiException.conflict("error.timeOff.alreadyGranted");
		}
		return write(actor, person, type, year, allowanceFor(type, allowanceOverride),
				allowanceOverride != null, employment.findByUserId(person.getId()).orElse(null), note);
	}

	/** The type a year may be granted for at all: one with a quota to hand out. */
	private TimeOffType grantableType(String typeId) {
		TimeOffType type = types.require(typeId);
		if (type.isUnlimited() || !type.countsAgainstBalance()) {
			throw ApiException.badRequest("error.timeOff.unlimitedHasNoBalance");
		}
		return type;
	}

	private static int allowanceFor(TimeOffType type, Integer override) {
		int allowance = override != null ? override : type.allowanceMilliDays();
		if (allowance < 0 || allowance > TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) {
			throw ApiException.badRequest("error.timeOff.allowanceInvalid");
		}
		return allowance;
	}

	/**
	 * One person's grant, written: the entitlement, the accrual behind it, the log and the notice.
	 *
	 * <p>Takes the employment record rather than reading it, so a bulk grant can read a page of
	 * them in one query. Everything that is the same for everybody in a bulk — the type, the
	 * allowance, the keeper check — is settled before this is called.
	 */
	private TimeOffEntitlement write(User actor, User person, TimeOffType type, int year,
			int allowance, boolean individual, TimeOffEmployment facts, String note) {
		TimeOffBalances.Accrued accrued = accrueWith(facts, type, allowance, year);
		TimeOffEntitlement entitlement = TimeOffEntitlement.builder()
				.userId(person.getId())
				.typeId(type.getId())
				.year(year)
				.allowanceMilliDays(allowance)
				.accruedMilliDays(accrued.milliDays())
				.reason(accrued.reason())
				.source(individual ? TimeOffEntitlement.Source.INDIVIDUAL
						: TimeOffEntitlement.Source.TYPE_DEFAULT)
				.note(note == null || note.isBlank() ? null : note.strip())
				.grantedBy(actor.getId())
				.build();
		TimeOffEntitlement saved;
		try {
			saved = entitlements.save(entitlement);
		}
		catch (DuplicateKeyException raced) {
			// Two keepers granting the same year from two screens. The index decided.
			throw ApiException.conflict("error.timeOff.alreadyGranted");
		}
		// A monthly type books what the months so far have earned; the yearly run adds a twelfth
		// each month after that (HIN-119). An annual type earns the year on its anchor day.
		int earned = TimeOffBalances.accruedBy(accrued, type.accrual(), type.yearAnchor(), year,
				LocalDate.now(clock));
		if (earned > 0) {
			book(TimeOffLedgerEntry.builder()
					.userId(person.getId())
					.typeId(type.getId())
					.year(year)
					.kind(TimeOffLedgerEntry.Kind.ACCRUAL)
					.milliDays(earned)
					.effectiveOn(TimeOffBalances.yearStart(year, type.yearAnchor()))
					.refId(saved.getId())
					.actorId(actor.getId())
					.build());
		}
		audit.event(AuditAction.TIME_OFF_ENTITLEMENT_CHANGED).actor(actor).target(person)
				.meta("type", String.valueOf(type.getKey()))
				.meta("year", String.valueOf(year))
				.meta("allowance", String.valueOf(allowance))
				.meta("accrued", String.valueOf(accrued.milliDays()))
				.meta("reason", String.valueOf(accrued.reason()))
				.log();
		// § 2 Abs. 1 S. 2 Nr. 8 NachwG: the annual leave belongs in what an employee is told.
		notifications.notifyTimeOffEntitlement(person, type.getName(), type.getSystemKey(), year,
				accrued.milliDays());
		return saved;
	}

	/** What {@link #grant} would do for each of [userIds], without doing it. */
	public List<GrantPreview> previewGrant(User actor, String typeId, int year, List<String> userIds,
			Integer allowanceOverride) {
		access.requireKeeper(actor);
		assertYearInRange(year);
		TimeOffType type = types.require(typeId);
		List<String> people = capped(userIds);
		int allowance = allowanceOverride != null ? allowanceOverride : type.allowanceMilliDays();
		// Two queries for the whole preview, whatever its length. A preview of five hundred people
		// that asked per person would be a thousand round trips for a screen that writes nothing —
		// and the keeper fires it again with every allowance they try.
		Set<String> already = grantedAlready(typeId, year, people);
		Map<String, TimeOffEmployment> facts = employmentOf(people);
		List<GrantPreview> preview = new ArrayList<>();
		for (String userId : people) {
			TimeOffBalances.Accrued accrued =
					accrueWith(facts.get(userId), type, allowance, year);
			preview.add(new GrantPreview(userId, accrued.milliDays(), accrued.reason(),
					already.contains(userId)));
		}
		return preview;
	}

	/**
	 * Grants a year to several people at once. Anybody already granted is skipped rather than
	 * refused: a keeper adding three new joiners to a year they granted in January should not have
	 * to deselect the other forty.
	 *
	 * <p>Four queries before the loop rather than nine inside it. The type in particular was being
	 * read once per person — five hundred reads of one document — and the duplicate check twice.
	 * What is left per person is what is genuinely per person: the entitlement, its accrual, the
	 * log entry and the notice.
	 */
	public List<TimeOffEntitlement> grantMany(User actor, String typeId, int year,
			List<String> userIds, Integer allowanceOverride) {
		access.requireKeeper(actor);
		assertYearInRange(year);
		List<String> people = capped(userIds);
		TimeOffType type = grantableType(typeId);
		int allowance = allowanceFor(type, allowanceOverride);
		Map<String, User> byId = new HashMap<>();
		for (User person : users.findAllById(people)) {
			byId.put(person.getId(), person);
		}
		Set<String> already = grantedAlready(typeId, year, people);
		Map<String, TimeOffEmployment> facts = employmentOf(people);
		List<TimeOffEntitlement> granted = new ArrayList<>();
		for (String userId : people) {
			User person = byId.get(userId);
			// An id nobody answers to is skipped rather than refused: a bulk grant is a list
			// somebody assembled on a screen, and one stale row should not lose the other forty.
			if (person == null || already.contains(userId)) {
				continue;
			}
			granted.add(write(actor, person, type, year, allowance, allowanceOverride != null,
					facts.get(userId), null));
		}
		return granted;
	}

	/** Who among [people] already has this type and year, in one query. */
	private Set<String> grantedAlready(String typeId, int year, List<String> people) {
		return entitlements.findByTypeIdAndYearAndUserIdIn(typeId, year, people).stream()
				.map(TimeOffEntitlement::getUserId)
				.collect(Collectors.toSet());
	}

	/** The joining and leaving dates of [people], in one query. */
	private Map<String, TimeOffEmployment> employmentOf(List<String> people) {
		Map<String, TimeOffEmployment> facts = new HashMap<>();
		for (TimeOffEmployment row : employment.findByUserIdIn(people)) {
			facts.put(row.getUserId(), row);
		}
		return facts;
	}

	private static List<String> capped(List<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			throw ApiException.badRequest("error.timeOff.nobodySelected");
		}
		if (userIds.size() > BULK_MAX) {
			throw ApiException.badRequest("error.timeOff.tooManySelected", BULK_MAX);
		}
		return userIds.stream().distinct().toList();
	}

	/**
	 * What a year is worth for somebody whose employment dates are already in hand.
	 *
	 * <p>The dates are passed in rather than read, so a bulk grant reads a page of them at once.
	 * The allowance overrides the type's, which is the whole point of an individual grant.
	 */
	private static TimeOffBalances.Accrued accrueWith(TimeOffEmployment facts, TimeOffType type,
			int allowance, int year) {
		TimeOffBalances.Rules rules = TimeOffBalances.Rules.of(type).withAllowance(allowance);
		return TimeOffBalances.accrue(rules, facts == null ? null : facts.getHiredOn(),
				facts == null ? null : facts.getLeftOn(), year);
	}

	// --- booking ------------------------------------------------------------------

	/**
	 * A keeper's correction: days added or taken away, with a reason, as a new row.
	 *
	 * <p>The reason is required and stays required. A balance that moved for no stated cause is the
	 * thing somebody will be asked about in a year and will not be able to answer.
	 */
	public TimeOffLedgerEntry adjust(User actor, String userId, String typeId, int year,
			int milliDays, LocalDate effectiveOn, String reason) {
		access.requireKeeper(actor);
		assertYearInRange(year);
		User person = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
		// The same refusal a grant makes. Without it a keeper could book days against a type that
		// contractually has none — the sick type among them — and § 3 EFZG is continued pay, not a
		// quota somebody draws down.
		TimeOffType type = grantableType(typeId);
		if (milliDays == 0) {
			throw ApiException.badRequest("error.timeOff.adjustmentZero");
		}
		// Two-sided rather than Math.abs: abs(Integer.MIN_VALUE) is Integer.MIN_VALUE, which is
		// less than any ceiling, so the bound would let through the one value that overflows the
		// balance aggregation — into a row nothing can delete.
		if (milliDays < -TimeOffType.ALLOWANCE_MAX_MILLI_DAYS
				|| milliDays > TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) {
			throw ApiException.badRequest("error.timeOff.allowanceInvalid");
		}
		String text = reason == null ? "" : reason.strip();
		if (text.isEmpty()) {
			throw ApiException.badRequest("error.timeOff.reasonRequired");
		}
		if (text.length() > TimeOffLedgerEntry.REASON_MAX) {
			text = text.substring(0, TimeOffLedgerEntry.REASON_MAX);
		}
		TimeOffLedgerEntry entry = book(TimeOffLedgerEntry.builder()
				.userId(person.getId())
				.typeId(typeId)
				.year(year)
				.kind(TimeOffLedgerEntry.Kind.ADJUSTMENT)
				.milliDays(milliDays)
				.effectiveOn(effectiveOn != null ? effectiveOn
						: TimeOffBalances.yearStart(year, type.yearAnchor()))
				.reason(text)
				.actorId(actor.getId())
				.build());
		// The type, the year and the amount — never the sentence. The reason is free text, and
		// this module's own documentation names it as the place additional leave under § 208
		// SGB IX or a reduction under § 17 BEEG is written down: special categories of personal
		// data (Art. 9 DSGVO). On the ledger row it is visible to the person and their keepers and
		// goes when the row goes; in the admin audit log it would outlive the account, survive the
		// module being switched off, and be readable by every administrator. The same rule the
		// employment dates already follow.
		audit.event(AuditAction.TIME_OFF_LEDGER_BOOKED).actor(actor).target(person)
				.meta("type", String.valueOf(type.getKey()))
				.meta("year", String.valueOf(year))
				.meta("milliDays", String.valueOf(milliDays))
				.log();
		return entry;
	}

	/**
	 * What is left of [typeId] for [userId] in [year], without asking who wants to know.
	 *
	 * <p>For the approval flow, which has to answer "does this cover it?" for somebody who may not
	 * read the balance itself. A lead deciding leave for their team learns whether the days are
	 * there, never how many there are — that number is the person's (R2, R10), and the difference
	 * between a yes-or-no and a figure is the whole of it.
	 */
	int remainingMilliDays(String userId, String typeId, int year) {
		Map<TimeOffLedgerEntry.Kind, Integer> sums =
				sumsByType(userId, year).getOrDefault(typeId, Map.of());
		int total = 0;
		for (Integer value : sums.values()) {
			total += value == null ? 0 : value;
		}
		return total;
	}

	/**
	 * What is left of every type, for several people and several years, in one aggregation.
	 *
	 * <p>For a list of requests that has to say "the days are there" or "they are not" beside each
	 * row. Asking {@link #remainingMilliDays} per row would be one aggregation per row, which is
	 * the shape A1 spent a review round removing from the entitlement directory.
	 *
	 * <p>Keyed {@code userId → typeId → year}. Still a yes-or-no for whoever reads it: the figure
	 * belongs to the person, and what a decider is handed is whether it covers the request (R2,
	 * R10).
	 */
	Map<String, Map<String, Map<Integer, Integer>>> remainingByPerson(Collection<String> userIds,
			Collection<Integer> years) {
		if (userIds == null || userIds.isEmpty() || years == null || years.isEmpty()) {
			return Map.of();
		}
		AggregationResults<Document> results = mongo.aggregate(
				Aggregation.newAggregation(
						Aggregation.match(Criteria.where("userId").in(userIds).and("year").in(years)),
						Aggregation.group("userId", "typeId", "year").sum("milliDays").as("total")),
				TimeOffLedgerEntry.class, Document.class);
		Map<String, Map<String, Map<Integer, Integer>>> remaining = new LinkedHashMap<>();
		for (Document row : results) {
			Document id = row.get("_id", Document.class);
			String userId = id.getString("userId");
			String typeId = id.getString("typeId");
			Integer year = id.getInteger("year");
			if (userId == null || typeId == null || year == null) {
				continue;
			}
			remaining.computeIfAbsent(userId, key -> new LinkedHashMap<>())
					.computeIfAbsent(typeId, key -> new LinkedHashMap<>())
					.merge(year, toInt(row.get("total")), Integer::sum);
		}
		return remaining;
	}

	/** The one place a row enters the journal. Nothing updates one, and nothing deletes one. */
	TimeOffLedgerEntry book(TimeOffLedgerEntry entry) {
		return ledger.save(entry);
	}

	// --- employment dates -----------------------------------------------------------

	public Optional<TimeOffEmployment> employmentOf(User viewer, String userId) {
		User person = access.requireSubject(viewer, userId);
		return employment.findByUserId(person.getId());
	}

	/**
	 * Sets the joining and leaving dates. A keeper's, because they decide what everybody's
	 * entitlement is computed from — and because an employment date is a fact about a contract,
	 * not a preference.
	 */
	public TimeOffEmployment saveEmployment(User actor, String userId, LocalDate hiredOn,
			LocalDate leftOn, String note) {
		access.requireKeeper(actor);
		User person = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
		if (hiredOn != null && leftOn != null && leftOn.isBefore(hiredOn)) {
			throw ApiException.badRequest("error.timeOff.employmentOrder");
		}
		TimeOffEmployment facts = employment.findByUserId(person.getId())
				.orElseGet(() -> TimeOffEmployment.builder().userId(person.getId()).build());
		facts.setHiredOn(hiredOn);
		facts.setLeftOn(leftOn);
		facts.setNote(note == null || note.isBlank() ? null
				: note.strip().substring(0, Math.min(note.strip().length(), TimeOffEmployment.NOTE_MAX)));
		facts.setUpdatedBy(actor.getId());
		facts.setUpdatedAt(clock.instant());
		TimeOffEmployment saved = employment.save(facts);
		// The dates, never the note: the note is a keeper's sentence and the log outlives it.
		audit.event(AuditAction.TIME_OFF_EMPLOYMENT_CHANGED).actor(actor).target(person)
				.meta("hiredOn", String.valueOf(hiredOn))
				.meta("leftOn", String.valueOf(leftOn))
				.log();
		return saved;
	}

	/**
	 * Whether somebody keeps absences for everybody — the one thing a screen needs to know before
	 * it offers a way into the keeper's pages.
	 *
	 * <p>Asked rather than derived from the admin role, because a keeper need not be one: an
	 * operator names the circle that sees sick days as sick days (Art. 9 DSGVO), and a named
	 * keeper who could not find the page would be a list that configures nothing.
	 */
	public boolean isKeeper(User viewer) {
		return access.isKeeper(viewer);
	}

	// --- the statutory floor ------------------------------------------------------

	/**
	 * The working days in somebody's week today — read once per screen rather than once per type,
	 * because the floor is a property of the week and not of each type.
	 */
	public int workingDaysPerWeek(String userId) {
		return workWeek.workingDaysPerWeek(userId, LocalDate.now(clock));
	}

	private void assertYearInRange(int year) {
		int today = LocalDate.now(clock).getYear();
		if (Math.abs(year - today) > TimeOffEntitlement.YEARS_AROUND_TODAY) {
			throw ApiException.badRequest("error.timeOff.yearOutOfRange",
					TimeOffEntitlement.YEARS_AROUND_TODAY);
		}
	}
}
