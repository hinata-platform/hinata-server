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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
			boolean granted, boolean unlimited, TimeOffBalances.Reason reason) {
	}

	/** What a bulk grant would do, before it does it. */
	public record GrantPreview(String userId, int accruedMilliDays, TimeOffBalances.Reason reason,
			boolean alreadyGranted) {
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
		// A retired type with history still has to answer for itself.
		for (String typeId : sums.keySet()) {
			byId.computeIfAbsent(typeId, id -> types.require(id));
		}
		List<Balance> balances = new ArrayList<>();
		for (TimeOffType type : byId.values()) {
			if (!type.isActive() && !sums.containsKey(type.getId())
					&& !granted.containsKey(type.getId())) {
				continue;
			}
			balances.add(balance(type, year, sums.get(type.getId()),
					planned.getOrDefault(type.getId(), 0), granted.get(type.getId())));
		}
		return balances;
	}

	private Balance balance(TimeOffType type, int year,
			Map<TimeOffLedgerEntry.Kind, Integer> sums, int planned, TimeOffEntitlement granted) {
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
		return new Balance(type.getId(), year,
				granted == null ? 0 : granted.allowanceMilliDays(),
				accrual, carriedIn, adjusted,
				Math.max(0, takenTotal - planned), planned, expired, paidOut, remaining,
				carryoverExpiry(type, year), granted != null, type.isUnlimited(),
				granted == null ? null : reasonOf(granted));
	}

	private static TimeOffBalances.Reason reasonOf(TimeOffEntitlement granted) {
		if (granted.accruedMilliDays() == 0) {
			return TimeOffBalances.Reason.NOT_EMPLOYED;
		}
		return granted.accruedMilliDays() < granted.allowanceMilliDays()
				? TimeOffBalances.Reason.WAITING_PERIOD : TimeOffBalances.Reason.FULL;
	}

	/** When what is carried into [year] runs out, for a type that carries anything at all. */
	private static LocalDate carryoverExpiry(TimeOffType type, int year) {
		if (type.carryover() == TimeOffType.Carryover.NONE) {
			return null;
		}
		return type.carryoverExpiresOn().atYear(year + 1);
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

	/** What is booked but still ahead of the person: days committed, not days gone. */
	private Map<String, Integer> plannedByType(String userId, int year) {
		AggregationResults<Document> results = mongo.aggregate(
				Aggregation.newAggregation(
						Aggregation.match(Criteria.where("userId").is(userId).and("year").is(year)
								.and("kind").is(TimeOffLedgerEntry.Kind.BOOKED.name())
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
		TimeOffType type = types.require(typeId);
		if (type.isUnlimited() || !type.countsAgainstBalance()) {
			throw ApiException.badRequest("error.timeOff.unlimitedHasNoBalance");
		}
		if (entitlements.findByUserIdAndTypeIdAndYear(person.getId(), typeId, year).isPresent()) {
			throw ApiException.conflict("error.timeOff.alreadyGranted");
		}
		int allowance = allowanceOverride != null ? allowanceOverride : type.allowanceMilliDays();
		if (allowance < 0 || allowance > TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) {
			throw ApiException.badRequest("error.timeOff.allowanceInvalid");
		}
		TimeOffBalances.Accrued accrued = accrueFor(person.getId(), type, allowance, year);
		TimeOffEntitlement entitlement = TimeOffEntitlement.builder()
				.userId(person.getId())
				.typeId(typeId)
				.year(year)
				.allowanceMilliDays(allowance)
				.accruedMilliDays(accrued.milliDays())
				.source(allowanceOverride != null ? TimeOffEntitlement.Source.INDIVIDUAL
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
		if (accrued.milliDays() > 0) {
			book(TimeOffLedgerEntry.builder()
					.userId(person.getId())
					.typeId(typeId)
					.year(year)
					.kind(TimeOffLedgerEntry.Kind.ACCRUAL)
					.milliDays(accrued.milliDays())
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
		List<GrantPreview> preview = new ArrayList<>();
		for (String userId : people) {
			boolean already = entitlements.findByUserIdAndTypeIdAndYear(userId, typeId, year).isPresent();
			TimeOffBalances.Accrued accrued = accrueFor(userId, type, allowance, year);
			preview.add(new GrantPreview(userId, accrued.milliDays(), accrued.reason(), already));
		}
		return preview;
	}

	/**
	 * Grants a year to several people at once. Anybody already granted is skipped rather than
	 * refused: a keeper adding three new joiners to a year they granted in January should not have
	 * to deselect the other forty.
	 */
	public List<TimeOffEntitlement> grantMany(User actor, String typeId, int year,
			List<String> userIds, Integer allowanceOverride) {
		access.requireKeeper(actor);
		List<TimeOffEntitlement> granted = new ArrayList<>();
		for (String userId : capped(userIds)) {
			if (entitlements.findByUserIdAndTypeIdAndYear(userId, typeId, year).isPresent()) {
				continue;
			}
			granted.add(grant(actor, userId, typeId, year, allowanceOverride, null));
		}
		return granted;
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

	private TimeOffBalances.Accrued accrueFor(String userId, TimeOffType type, int allowance,
			int year) {
		TimeOffEmployment facts = employment.findByUserId(userId).orElse(null);
		TimeOffBalances.Rules rules = new TimeOffBalances.Rules(allowance, type.yearAnchor(),
				type.waitingPeriodMonths(), type.prorateOnJoin(), type.prorateOnLeave());
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
		TimeOffType type = types.require(typeId);
		if (milliDays == 0) {
			throw ApiException.badRequest("error.timeOff.adjustmentZero");
		}
		if (Math.abs(milliDays) > TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) {
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
		audit.event(AuditAction.TIME_OFF_LEDGER_BOOKED).actor(actor).target(person)
				.meta("type", String.valueOf(type.getKey()))
				.meta("year", String.valueOf(year))
				.meta("milliDays", String.valueOf(milliDays))
				// The reason is an operator's sentence about an entitlement, not a diagnosis: a
				// sick type carries no allowance, so an adjustment is never about an illness.
				.meta("reason", text)
				.log();
		return entry;
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

	// --- the statutory floor ------------------------------------------------------

	/**
	 * Whether a type's allowance falls short of the statutory minimum for somebody's working week,
	 * and what that minimum is. A warning for the screen, never a refusal — see
	 * {@link TimeOffLegalFloor}.
	 */
	public record LegalFloor(int minimumMilliDays, int workingDaysPerWeek, boolean fallsShort) {
	}

	public LegalFloor legalFloor(String userId, TimeOffType type) {
		int days = workingDaysPerWeek(userId);
		return new LegalFloor(TimeOffLegalFloor.minimumMilliDays(days), days,
				TimeOffLegalFloor.fallsShort(type, days));
	}

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
