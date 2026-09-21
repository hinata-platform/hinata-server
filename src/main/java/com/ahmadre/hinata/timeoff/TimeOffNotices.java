package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telling people, in time, how much leave they still have and on which day it lapses (HIN-119).
 *
 * <p>The notice is what lets leave lapse at all: without it the yearly run carries the days on
 * rather than letting them go (BAG 19.02.2019 – 9 AZR 541/15). Three moments, all policy:
 *
 * <ul>
 * <li><b>Once a year</b> on the notice day — 1 October unless the operator chose another — about
 * the year's leave and the first day any of it would lapse.</li>
 * <li><b>Weeks before the end of the year</b>, for a type that carries nothing or only part: what
 * the end of the year would take.</li>
 * <li><b>Weeks before the carryover deadline</b>: what was carried in and is still untaken.</li>
 * </ul>
 *
 * <p>Each scheduled notice exists once, by its key, and a run that missed its day catches up while
 * the deadline has not passed. A keeper can send one by hand from the list of people nobody told —
 * and every notice, scheduled or not, is written with an audit entry the audit retention never
 * deletes. The person reads the same rows in their own list.
 */
@Service
@RequiredArgsConstructor
public class TimeOffNotices {

	/** People read per round trip. */
	static final int SLICE = 500;

	/** Longest page of a person's notices or of the missing list. */
	public static final int PAGE_MAX = 100;

	/** Most rows the missing list assembles before it pages them. */
	static final int MISSING_MAX = 5_000;

	private final TimeOffSettings settings;
	private final TimeTrackingSettings policy;
	private final TimeOffTypeRepository types;
	private final TimeOffAccess access;
	private final TimeOffNoticeRepository notices;
	private final TimeOffEmploymentRepository employment;
	private final TimeOffYearFigures figures;
	private final UserRepository users;
	private final NotificationService notifications;
	private final AuditService audit;
	private final Clock clock;

	/** Somebody whose days would lapse on a deadline nobody told them about. */
	public record Missing(String userId, String name, String typeId, int year, int milliDays, LocalDate deadline,
			boolean overdue) {
	}

	/** One notice the schedule says is due: which kind, about which year, until when. */
	private record Due(TimeOffNotice.Kind kind, int year, LocalDate deadline) {
	}

	// --- the schedule -----------------------------------------------------------------

	/** Sends what is due today and returns how many notices went out. */
	int run() {
		if (!settings.enabled()) {
			return 0;
		}
		LocalDate today = LocalDate.now(clock);
		int sent = 0;
		for (TimeOffType type : types.findAll()) {
			if (type.countsAgainstBalance() && !type.isUnlimited() && type.isActive()) {
				sent += sendDue(type, today);
			}
		}
		return sent;
	}

	private int sendDue(TimeOffType type, LocalDate today) {
		int year = TimeOffBalances.leaveYearOf(today, type.yearAnchor());
		List<Due> due = dueOn(type, year, today);
		if (due.isEmpty()) {
			return 0;
		}
		int sent = 0;
		List<String> people = figures.peopleWith(type.getId(), List.of(year));
		for (int start = 0; start < people.size(); start += SLICE) {
			List<String> slice = people.subList(start, Math.min(people.size(), start + SLICE));
			Map<String, Map<Integer, TimeOffYearFigures.Year>> sums = figures.sums(type.getId(), List.of(year), slice);
			Map<String, User> present = presentAmong(slice, today);
			for (Due notice : due) {
				for (String userId : slice) {
					User person = present.get(userId);
					int milliDays = amount(type, notice.kind(), TimeOffYearFigures.yearOf(sums, userId, year));
					if (person == null || milliDays <= 0) {
						continue;
					}
					String key = "notice:" + notice.kind() + ":" + userId + ":" + type.getId() + ":" + notice.year();
					if (write(null, person, type, notice.kind(), notice.year(), milliDays, notice.deadline(), key)
							!= null) {
						sent++;
					}
				}
			}
		}
		return sent;
	}

	/** Which notices the schedule has due today for [type]'s leave year [year]. */
	private List<Due> dueOn(TimeOffType type, int year, LocalDate today) {
		TimeTrackingSettings.ExpiryNotice rule = policy.expiryNotice();
		List<Due> due = new ArrayList<>();
		LocalDate annual = TimeOffBalances.dayInYear(rule.annualOn(), year, type.yearAnchor());
		LocalDate first = firstDeadline(type, year);
		if (!today.isBefore(annual) && !today.isAfter(first)) {
			due.add(new Due(TimeOffNotice.Kind.ANNUAL, year, first));
		}
		if (type.carryover() != TimeOffType.Carryover.UNLIMITED) {
			LocalDate end = TimeOffBalances.yearEnd(year, type.yearAnchor());
			if (!today.isBefore(end.minusWeeks(rule.weeksBefore())) && !today.isAfter(end)) {
				due.add(new Due(TimeOffNotice.Kind.BEFORE_YEAR_END, year, end));
			}
		}
		LocalDate carried = TimeOffBalances.carryoverDeadline(type.carryoverExpiresOn(), year - 1, type.yearAnchor());
		if (!today.isBefore(carried.minusWeeks(rule.weeksBefore())) && !today.isAfter(carried)) {
			due.add(new Due(TimeOffNotice.Kind.BEFORE_CARRYOVER_DEADLINE, year - 1, carried));
		}
		return due;
	}

	/**
	 * The first day any of a year's leave would lapse: the end of the year for a type that carries
	 * nothing or only part of it, otherwise the carryover deadline in the year after.
	 */
	static LocalDate firstDeadline(TimeOffType type, int year) {
		return type.carryover() == TimeOffType.Carryover.UNLIMITED
				? TimeOffBalances.carryoverDeadline(type.carryoverExpiresOn(), year, type.yearAnchor())
				: TimeOffBalances.yearEnd(year, type.yearAnchor());
	}

	/** How much a notice of [kind] is about, for somebody whose current year reads [now]. */
	private static int amount(TimeOffType type, TimeOffNotice.Kind kind, TimeOffYearFigures.Year now) {
		return switch (kind) {
			case ANNUAL, MANUAL -> now.remaining();
			case BEFORE_YEAR_END -> now.remaining() - TimeOffYearRun.carryOf(type, Math.max(0, now.remaining()));
			case BEFORE_CARRYOVER_DEADLINE -> now.carriedUnused();
		};
	}

	/** The active accounts among [ids] that have not left before [today]. */
	private Map<String, User> presentAmong(List<String> ids, LocalDate today) {
		Map<String, LocalDate> leftOn = new HashMap<>();
		for (TimeOffEmployment row : employment.findByUserIdIn(ids)) {
			if (row.getLeftOn() != null) {
				leftOn.put(row.getUserId(), row.getLeftOn());
			}
		}
		Map<String, User> present = new HashMap<>();
		for (User person : users.findAllById(ids)) {
			LocalDate left = leftOn.get(person.getId());
			if (person.isActive() && (left == null || !left.isBefore(today))) {
				present.put(person.getId(), person);
			}
		}
		return present;
	}

	// --- by hand -------------------------------------------------------------------

	/**
	 * A keeper sending a notice now, about [year]'s leave: the current year's, or what was carried
	 * in from the year before. Refused when there is nothing to tell.
	 */
	public TimeOffNotice sendNow(User actor, String userId, String typeId, int year) {
		access.requireKeeper(actor);
		User person = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
		TimeOffType type = types.findById(typeId).orElseThrow(() -> ApiException.notFound("timeOffType"));
		if (!type.countsAgainstBalance() || type.isUnlimited()) {
			throw ApiException.badRequest("error.timeOff.unlimitedHasNoBalance");
		}
		LocalDate today = LocalDate.now(clock);
		int current = TimeOffBalances.leaveYearOf(today, type.yearAnchor());
		TimeOffYearFigures.Year now = TimeOffYearFigures.yearOf(
				figures.sums(typeId, List.of(current), List.of(userId)), userId, current);
		int milliDays;
		LocalDate deadline;
		if (year == current) {
			milliDays = now.remaining();
			deadline = firstDeadline(type, current);
		}
		else if (year == current - 1) {
			milliDays = now.carriedUnused();
			deadline = TimeOffBalances.carryoverDeadline(type.carryoverExpiresOn(), current - 1, type.yearAnchor());
		}
		else {
			throw ApiException.badRequest("error.timeOff.noticeYearInvalid");
		}
		if (milliDays <= 0) {
			throw ApiException.badRequest("error.timeOff.nothingToNotify");
		}
		return write(actor, person, type, TimeOffNotice.Kind.MANUAL, year, milliDays, deadline, null);
	}

	/**
	 * Writes one notice and delivers it: the row, the bell, mail and push, and the audit entry.
	 * Null when a scheduled notice with the same key already exists.
	 */
	private TimeOffNotice write(User actor, User person, TimeOffType type, TimeOffNotice.Kind kind, int year,
			int milliDays, LocalDate deadline, String runKey) {
		TimeOffNotice notice;
		try {
			notice = notices.insert(TimeOffNotice.builder()
					.userId(person.getId()).typeId(type.getId()).year(year).kind(kind)
					.remainingMilliDays(milliDays).expiresOn(deadline).sentAt(clock.instant())
					.actorId(actor == null ? null : actor.getId()).runKey(runKey).build());
		}
		catch (DuplicateKeyException already) {
			return null;
		}
		notifications.notifyTimeOffExpiry(person, type.getName(), type.getSystemKey(), milliDays, deadline);
		var event = audit.event(AuditAction.TIME_OFF_EXPIRY_NOTICE_SENT).target(person)
				.meta("notice", notice.getId())
				.meta("kind", kind.name())
				.meta("type", String.valueOf(type.getKey()))
				.meta("year", String.valueOf(year))
				.meta("milliDays", String.valueOf(milliDays))
				.meta("expiresOn", String.valueOf(deadline));
		if (actor != null) {
			event.actor(actor);
		}
		event.log();
		return notice;
	}

	// --- reading ------------------------------------------------------------------

	/** The notices one person received, newest first: their own, or anybody's for a keeper. */
	public Page<TimeOffNotice> of(User viewer, String userId, int page, int size) {
		User person = access.requireSubject(viewer, userId);
		return notices.findByUserIdOrderBySentAtDesc(person.getId(),
				PageRequest.of(Math.max(0, page), Math.clamp(size, 1, PAGE_MAX)));
	}

	/**
	 * Who would lose days on a deadline without having been told — the keeper's list with the
	 * button to send the notice now.
	 *
	 * <p>Carried days past their deadline are overdue: the run held them because nobody was told,
	 * and they stay until a notice before a later deadline lets them go. The end of the year shows
	 * up once the pre-deadline notice would have been due.
	 */
	public Page<Missing> missing(User actor, int page, int size) {
		access.requireKeeper(actor);
		LocalDate today = LocalDate.now(clock);
		int weeks = policy.expiryNotice().weeksBefore();
		List<Missing> rows = new ArrayList<>();
		Map<String, String> names = new HashMap<>();
		for (TimeOffType type : types.findAll()) {
			if (!type.countsAgainstBalance() || type.isUnlimited() || rows.size() >= MISSING_MAX) {
				continue;
			}
			int year = TimeOffBalances.leaveYearOf(today, type.yearAnchor());
			LocalDate carried = TimeOffBalances.carryoverDeadline(type.carryoverExpiresOn(), year - 1,
					type.yearAnchor());
			LocalDate end = TimeOffBalances.yearEnd(year, type.yearAnchor());
			boolean yearEndDue = type.carryover() != TimeOffType.Carryover.UNLIMITED
					&& !today.isBefore(end.minusWeeks(weeks));
			List<String> people = figures.peopleWith(type.getId(), List.of(year));
			for (int start = 0; start < people.size() && rows.size() < MISSING_MAX; start += SLICE) {
				List<String> slice = people.subList(start, Math.min(people.size(), start + SLICE));
				Map<String, Map<Integer, TimeOffYearFigures.Year>> sums =
						figures.sums(type.getId(), List.of(year), slice);
				Map<String, Instant> toldLast = figures.firstNotices(type.getId(), year - 1, slice);
				Map<String, Instant> toldNow = yearEndDue ? figures.firstNotices(type.getId(), year, slice) : Map.of();
				Map<String, User> present = presentAmong(slice, today);
				present.forEach((id, person) -> names.put(id, person.getDisplayName()));
				for (String userId : slice) {
					if (!present.containsKey(userId)) {
						continue;
					}
					TimeOffYearFigures.Year now = TimeOffYearFigures.yearOf(sums, userId, year);
					int unused = now.carriedUnused();
					if (unused > 0 && !TimeOffYearFigures.toldBefore(toldLast.get(userId), carried.plusDays(1))) {
						rows.add(new Missing(userId, names.get(userId), type.getId(), year - 1, unused, carried,
								today.isAfter(carried)));
					}
					int lapsing = amount(type, TimeOffNotice.Kind.BEFORE_YEAR_END, now);
					if (yearEndDue && lapsing > 0 && toldNow.get(userId) == null) {
						rows.add(new Missing(userId, names.get(userId), type.getId(), year, lapsing, end, false));
					}
				}
			}
		}
		rows.sort(Comparator.comparing(Missing::deadline)
				.thenComparing(row -> row.name() == null ? "" : row.name(), String.CASE_INSENSITIVE_ORDER));
		int pageSize = Math.clamp(size, 1, PAGE_MAX);
		int from = Math.min(rows.size(), Math.max(0, page) * pageSize);
		int to = Math.min(rows.size(), from + pageSize);
		return new PageImpl<>(rows.subList(from, to), PageRequest.of(Math.max(0, page), pageSize), rows.size());
	}
}
