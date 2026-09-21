package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The turn of the leave year, run every night (HIN-119): monthly accruals, carrying what is left
 * into the new year, letting carried days lapse after their deadline, and proposing a lapse after a
 * long illness.
 *
 * <p><b>It writes bookings, never totals.</b> Every row it books carries a {@code runKey} that
 * exists once — {@code carry-in:<user>:<type>:<year>} and the like — and the journal's unique index
 * on it is what makes the run idempotent: running twice, or on two instances at once, collides on
 * the key instead of carrying the same days twice. The night itself is claimed by inserting
 * {@link TimeOffYearRunRecord}, so the counts on the admin page come from one run.
 *
 * <p><b>Nothing lapses without a notice (R12).</b> Since BAG 19.02.2019 – 9 AZR 541/15 leave lapses
 * only if the employer told the employee in time how much there is and that it lapses. So a lapse
 * is booked only where {@link TimeOffNotice} shows such a notice before the deadline. Where there is
 * none, the days are carried on in full — lost to nobody, and counted as "held" — until a notice
 * before a later deadline lets them go, or a keeper decides after a long illness.
 *
 * <p><b>Long illness is proposed, not decided.</b> Fifteen months after the end of a leave year,
 * days that could not be taken because of illness lapse without a notice (EuGH C-214/10 <i>KHS</i>;
 * BAG 20.12.2022 – 9 AZR 245/19). Whether somebody really could not work all that time is a finding
 * about their health, so the run opens a {@link TimeOffProposal} and a keeper confirms it with a
 * reason (Art. 22 DSGVO).
 *
 * <p>Checks the module switch itself: a job never passes the HTTP gate (see {@link TimeOffSettings}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TimeOffYearRun {

	/** People read per round trip. */
	static final int SLICE = 500;

	/** Months after the end of a leave year after which illness lets leave lapse. */
	static final int LONG_ILLNESS_MONTHS = 15;

	/**
	 * The share of the window somebody has to have been sick for the run to propose anything. Half:
	 * low enough that a keeper sees the cases worth looking at, and the decision stays theirs.
	 */
	static final int LONG_ILLNESS_SHARE_PERCENT = 50;

	private final TimeOffSettings settings;
	private final TimeOffTypeRepository types;
	private final TimeOffEntitlementRepository entitlements;
	private final TimeOffBalanceService balances;
	private final TimeOffProposalRepository proposals;
	private final TimeOffYearRunRepository runs;
	private final TimeOffYearFigures figures;
	private final TimeOffAbsences absences;
	private final UserRepository users;
	private final NotificationService notifications;
	private final AuditService audit;
	private final Clock clock;

	/** Tonight's run, or empty when the module is off or another instance has the night. */
	Optional<TimeOffYearRunRecord> run() {
		if (!settings.enabled()) {
			return Optional.empty();
		}
		LocalDate today = LocalDate.now(clock);
		TimeOffYearRunRecord record;
		try {
			record = runs.insert(TimeOffYearRunRecord.builder().id(today.toString())
					.startedAt(clock.instant()).build());
		}
		catch (DuplicateKeyException claimed) {
			return Optional.empty();
		}
		try {
			for (TimeOffType type : types.findAll()) {
				if (type.countsAgainstBalance() && !type.isUnlimited()) {
					runType(type, today, record);
				}
			}
		}
		catch (RuntimeException ex) {
			// Every booking so far carries its key, so tomorrow's run carries on where this one
			// stopped without doing anything twice.
			record.setFailed(true);
			log.warn("[timeoff] yearly run stopped: {}", ex.toString());
		}
		finally {
			record.setFinishedAt(clock.instant());
			runs.save(record);
			audit.event(AuditAction.TIME_OFF_YEAR_RUN)
					.meta("accrued", String.valueOf(record.getAccrued()))
					.meta("carried", String.valueOf(record.getCarried()))
					.meta("expired", String.valueOf(record.getExpired()))
					.meta("held", String.valueOf(record.getHeld()))
					.meta("proposals", String.valueOf(record.getProposals()))
					.meta("failed", String.valueOf(record.isFailed()))
					.log();
		}
		return Optional.of(record);
	}

	private void runType(TimeOffType type, LocalDate today, TimeOffYearRunRecord record) {
		int year = TimeOffBalances.leaveYearOf(today, type.yearAnchor());
		if (type.accrual() == TimeOffType.Accrual.MONTHLY) {
			accrueMonthly(type, year, today, record);
		}
		List<String> people = figures.peopleWith(type.getId(), List.of(year - 1, year));
		for (int start = 0; start < people.size(); start += SLICE) {
			turnYear(type, year, today, people.subList(start, Math.min(people.size(), start + SLICE)), record);
		}
	}

	// --- monthly accrual -------------------------------------------------------------

	/**
	 * Tops up a monthly type to what the months so far have earned. The grant booked what had been
	 * earned on the day it was made; each month after that adds a twelfth, rounded the way § 5
	 * Abs. 2 BUrlG rounds, and never beyond what the year is worth.
	 */
	private void accrueMonthly(TimeOffType type, int year, LocalDate today, TimeOffYearRunRecord record) {
		LocalDate start = TimeOffBalances.yearStart(year, type.yearAnchor());
		LocalDate end = TimeOffBalances.yearEnd(year, type.yearAnchor());
		LocalDate until = today.isAfter(end) ? end : today;
		int months = TimeOffBalances.fullMonths(start, until);
		int page = 0;
		Page<TimeOffEntitlement> grants;
		do {
			grants = entitlements.findByTypeIdAndYear(type.getId(), year,
					PageRequest.of(page++, SLICE, Sort.by("userId")));
			List<String> ids = grants.getContent().stream().map(TimeOffEntitlement::getUserId).toList();
			Map<String, Map<Integer, TimeOffYearFigures.Year>> sums = figures.sums(type.getId(), List.of(year), ids);
			for (TimeOffEntitlement grant : grants) {
				TimeOffBalances.Accrued worth = new TimeOffBalances.Accrued(grant.accruedMilliDays(), 12,
						TimeOffBalances.Reason.FULL);
				int earned = TimeOffBalances.accruedBy(worth, TimeOffType.Accrual.MONTHLY, type.yearAnchor(), year,
						until);
				int booked = TimeOffYearFigures.yearOf(sums, grant.getUserId(), year)
						.of(TimeOffLedgerEntry.Kind.ACCRUAL);
				if (earned > booked && bookOnce(TimeOffLedgerEntry.builder()
						.userId(grant.getUserId()).typeId(type.getId()).year(year)
						.kind(TimeOffLedgerEntry.Kind.ACCRUAL).milliDays(earned - booked).effectiveOn(until)
						.refId(grant.getId())
						.runKey("accrue:" + grant.getUserId() + ":" + type.getId() + ":" + year + ":" + months)
						.build())) {
					record.setAccrued(record.getAccrued() + 1);
				}
			}
		}
		while (grants.hasNext());
	}

	// --- the turn of the year ---------------------------------------------------------

	private void turnYear(TimeOffType type, int year, LocalDate today, List<String> slice,
			TimeOffYearRunRecord record) {
		String typeId = type.getId();
		Map<String, Map<Integer, TimeOffYearFigures.Year>> sums = figures.sums(typeId, List.of(year - 1, year), slice);
		Map<String, java.time.Instant> toldAboutLast = figures.firstNotices(typeId, year - 1, slice);
		Map<String, Integer> originsLast = figures.originYears(typeId, year - 1, slice);
		Map<String, Integer> originsNow = figures.originYears(typeId, year, slice);
		Map<String, User> people = new HashMap<>();
		for (User person : users.findAllById(slice)) {
			people.put(person.getId(), person);
		}
		LocalDate yearStart = TimeOffBalances.yearStart(year, type.yearAnchor());
		LocalDate lastYearEnd = yearStart.minusDays(1);
		LocalDate carriedDeadline = TimeOffBalances.carryoverDeadline(type.carryoverExpiresOn(), year - 1,
				type.yearAnchor());
		for (String userId : slice) {
			TimeOffYearFigures.Year last = TimeOffYearFigures.yearOf(sums, userId, year - 1);
			TimeOffYearFigures.Year now = TimeOffYearFigures.yearOf(sums, userId, year);
			java.time.Instant told = toldAboutLast.get(userId);

			// The end of last year: carry what the type carries, let the rest lapse — if they were told.
			int left = last.remaining();
			Integer origin = originsNow.get(userId);
			if (left > 0) {
				boolean toldInTime = TimeOffYearFigures.toldBefore(told, yearStart);
				int policyCarry = carryOf(type, left);
				int carry = toldInTime ? policyCarry : left;
				int lapse = toldInTime ? left - policyCarry : 0;
				if (!toldInTime && left > policyCarry) {
					record.setHeld(record.getHeld() + 1);
				}
				// Days that were already held last year keep the year they came from.
				origin = toldInTime || last.carriedUnused() == 0 ? Integer.valueOf(year - 1)
						: originsLast.getOrDefault(userId, year - 2);
				boolean carried = carry > 0 && carryOver(type, userId, year, carry, lastYearEnd, yearStart, origin);
				boolean lapsed = lapse > 0 && bookOnce(TimeOffLedgerEntry.builder()
						.userId(userId).typeId(typeId).year(year - 1).kind(TimeOffLedgerEntry.Kind.EXPIRED)
						.milliDays(-lapse).effectiveOn(lastYearEnd)
						.runKey("lapse-year:" + userId + ":" + typeId + ":" + (year - 1)).build());
				if (carried) {
					record.setCarried(record.getCarried() + 1);
					now = now.plus(TimeOffLedgerEntry.Kind.CARRYOVER_IN, carry);
				}
				if (lapsed) {
					record.setExpired(record.getExpired() + 1);
				}
				if (carried || lapsed) {
					notifications.notifyTimeOffBalanceSummary(people.get(userId), type.getName(),
							type.getSystemKey(), year - 1, carried ? carry : 0, lapsed ? lapse : 0, carriedDeadline);
				}
			}

			// The carryover deadline: what came in and was not taken lapses — if they were told.
			int unused = now.carriedUnused();
			if (today.isAfter(carriedDeadline) && unused > 0) {
				if (TimeOffYearFigures.toldBefore(told, carriedDeadline.plusDays(1))) {
					if (bookOnce(TimeOffLedgerEntry.builder()
							.userId(userId).typeId(typeId).year(year).kind(TimeOffLedgerEntry.Kind.EXPIRED)
							.milliDays(-unused).effectiveOn(carriedDeadline)
							.runKey("lapse-carried:" + userId + ":" + typeId + ":" + year).build())) {
						record.setExpired(record.getExpired() + 1);
					}
				}
				else {
					record.setHeld(record.getHeld() + 1);
					proposeAfterIllness(type, userId, year, origin, unused, today, record);
				}
			}
		}
	}

	/** What a type carries of [left]: nothing, everything, or up to its cap. */
	static int carryOf(TimeOffType type, int left) {
		return switch (type.carryover()) {
			case NONE -> 0;
			case UNLIMITED -> left;
			case CAPPED -> Math.min(left, type.getCarryoverCapMilliDays() == null ? 0
					: type.getCarryoverCapMilliDays());
		};
	}

	/**
	 * The pair of rows a carryover is: out of the old year, into the new one. Each exists once by
	 * its key, so a run that stopped between the two books the missing half next time.
	 */
	private boolean carryOver(TimeOffType type, String userId, int year, int carry, LocalDate lastYearEnd,
			LocalDate yearStart, Integer origin) {
		boolean out = bookOnce(TimeOffLedgerEntry.builder()
				.userId(userId).typeId(type.getId()).year(year - 1).kind(TimeOffLedgerEntry.Kind.CARRYOVER_OUT)
				.milliDays(-carry).effectiveOn(lastYearEnd)
				.runKey("carry-out:" + userId + ":" + type.getId() + ":" + (year - 1)).build());
		boolean in = bookOnce(TimeOffLedgerEntry.builder()
				.userId(userId).typeId(type.getId()).year(year).kind(TimeOffLedgerEntry.Kind.CARRYOVER_IN)
				.milliDays(carry).effectiveOn(yearStart).originYear(origin)
				.runKey("carry-in:" + userId + ":" + type.getId() + ":" + year).build());
		return out || in;
	}

	/**
	 * Opens a proposal for days held since [origin] once fifteen months have passed since that year
	 * ended, if the person was sick for at least half of the time in between. Once per person, type
	 * and year, by its key.
	 */
	private void proposeAfterIllness(TimeOffType type, String userId, int year, Integer origin, int unused,
			LocalDate today, TimeOffYearRunRecord record) {
		if (origin == null) {
			return;
		}
		LocalDate from = TimeOffBalances.yearStart(origin, type.yearAnchor());
		LocalDate lapseDay = TimeOffBalances.yearEnd(origin, type.yearAnchor()).plusMonths(LONG_ILLNESS_MONTHS);
		if (!today.isAfter(lapseDay)) {
			return;
		}
		long window = ChronoUnit.DAYS.between(from, lapseDay) + 1;
		int sick = absences.sickDays(userId, from, lapseDay);
		if (sick * 100L < window * LONG_ILLNESS_SHARE_PERCENT) {
			return;
		}
		try {
			proposals.insert(TimeOffProposal.builder()
					.userId(userId).typeId(type.getId()).year(origin).heldIn(year).milliDays(unused)
					.sickDays(sick).windowFrom(from).windowTo(lapseDay)
					.status(TimeOffProposal.Status.OPEN).createdAt(clock.instant())
					.runKey("proposal:" + userId + ":" + type.getId() + ":" + origin).build());
			record.setProposals(record.getProposals() + 1);
		}
		catch (DuplicateKeyException already) {
			// Proposed on an earlier night; the keeper's list already has it.
		}
	}

	/** Books [entry] unless its key already exists; whether this call was the one that booked it. */
	private boolean bookOnce(TimeOffLedgerEntry entry) {
		try {
			balances.book(entry);
			return true;
		}
		catch (DuplicateKeyException already) {
			return false;
		}
	}
}
