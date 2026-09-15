package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Working-time patterns: a person keeps their own, an administrator keeps anybody's.
 *
 * <p>Leads have no say here. A pattern is planning information the person states about
 * themselves; a lead who could set it would be deciding somebody's hours, which is a different
 * feature with a different legal question (§ 87 Abs. 1 Nr. 2 BetrVG).
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

	private final WorkingScheduleRepository schedules;
	private final HolidayCalendarRepository calendars;
	private final AvailabilityAccess access;
	private final UserRepository users;
	private final SettingsService settings;
	private final HinataProperties properties;
	private final AuditService audit;
	private final Clock clock;

	/** A person's patterns: the one that applies today, if any, and all of them, newest first. */
	public record Patterns(String userId, List<Integer> defaultMinutesPerWeekday, String defaultCalendarId,
			WorkingSchedule current, List<WorkingSchedule> history) {
	}

	public Patterns of(User viewer, String userId) {
		User person = access.requireKeeper(viewer, userId);
		LocalDate today = today(person);
		List<WorkingSchedule> history = schedules.findByUserIdOrderByValidFromDesc(person.getId(),
				PageRequest.of(0, WorkingSchedule.HISTORY_MAX));
		WorkingSchedule current = history.stream().filter(pattern -> !pattern.getValidFrom().isAfter(today))
				.findFirst().orElse(null);
		return new Patterns(person.getId(), properties.getAvailability().getDefaultWeekdayMinutes(),
				calendars.findFirstByDefaultCalendarTrue().map(HolidayCalendar::getId).orElse(null), current,
				history);
	}

	/**
	 * Saves the pattern that applies from [validFrom] (today when absent). A pattern already starting
	 * that day is replaced; any other day adds one, up to {@link WorkingSchedule#HISTORY_MAX}.
	 */
	public WorkingSchedule save(User viewer, String userId, LocalDate validFrom, List<Integer> minutesPerWeekday,
			String holidayCalendarId) {
		User person = access.requireKeeper(viewer, userId);
		assertPattern(minutesPerWeekday);
		if (holidayCalendarId != null && !calendars.existsById(holidayCalendarId)) {
			throw ApiException.notFound("holidayCalendar");
		}
		LocalDate from = validFrom != null ? validFrom : today(person);
		CapacityService.assertWindow(from, from);
		WorkingSchedule existing = schedules.findByUserIdAndValidFrom(person.getId(), from).orElse(null);
		if (existing == null && schedules.countByUserId(person.getId()) >= WorkingSchedule.HISTORY_MAX) {
			throw ApiException.badRequest("error.availability.patternsTooMany", WorkingSchedule.HISTORY_MAX);
		}
		WorkingSchedule pattern = existing != null ? existing
				: WorkingSchedule.builder().userId(person.getId()).validFrom(from).build();
		pattern.setMinutesPerWeekday(List.copyOf(minutesPerWeekday));
		pattern.setHolidayCalendarId(holidayCalendarId);
		pattern.setCreatedBy(viewer.getId());
		pattern.setUpdatedAt(clock.instant());
		WorkingSchedule saved;
		try {
			saved = schedules.save(pattern);
		}
		catch (DuplicateKeyException raced) {
			// Two saves for the same day from two devices: the other one won, and saying so beats
			// silently overwriting it.
			throw ApiException.conflict("error.availability.patternChanged");
		}
		recordForOther(viewer, person, "saved", saved.getValidFrom());
		return saved;
	}

	/** Removes a pattern; the days it covered fall back to the one before it, or the default. */
	public void delete(User viewer, String id) {
		WorkingSchedule pattern = schedules.findById(id).orElseThrow(() -> ApiException.notFound("workingSchedule"));
		if (!viewer.isAdmin() && !viewer.getId().equals(pattern.getUserId())) {
			// Not found rather than forbidden: whether somebody else has a pattern is not the
			// caller's to learn.
			throw ApiException.notFound("workingSchedule");
		}
		schedules.delete(pattern);
		users.findById(pattern.getUserId())
				.ifPresent(person -> recordForOther(viewer, person, "deleted", pattern.getValidFrom()));
	}

	static void assertPattern(List<Integer> minutesPerWeekday) {
		if (minutesPerWeekday == null || minutesPerWeekday.size() != 7 || minutesPerWeekday.stream()
				.anyMatch(minutes -> minutes == null || minutes < 0 || minutes > WorkingSchedule.DAY_MINUTES_MAX)) {
			throw ApiException.badRequest("error.availability.patternInvalid");
		}
	}

	private LocalDate today(User person) {
		return LocalDate.now(clock.withZone(UserZones.of(person, settings.get())));
	}

	private void recordForOther(User actor, User person, String change, LocalDate validFrom) {
		if (actor.getId().equals(person.getId())) {
			return;
		}
		audit.event(AuditAction.AVAILABILITY_SCHEDULE_CHANGED).actor(actor).target(person)
				.meta("change", change)
				.meta("validFrom", String.valueOf(validFrom))
				.log();
	}
}
