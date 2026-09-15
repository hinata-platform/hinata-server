package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Working-time patterns, absences and capacity over HTTP.
 *
 * <p>Behind {@code AdvancedTimeTrackingGate}, which covers {@code /api/v1/availability/**}: with the
 * module off, none of this exists. Who may read and keep what is decided by {@link AvailabilityAccess},
 * which the services and the capacity route ask; the one thing decided here is the shape a reader
 * gets, and a lead reading a member's absences gets no note, no id and no sick day.
 */
@Tag(name = "Availability")
@RestController
@RequestMapping("/api/v1/availability")
@RequiredArgsConstructor
public class AvailabilityController {

	private final ScheduleService schedules;
	private final TimeOffService timeOff;
	private final CapacityService capacity;
	private final AvailabilityAccess access;
	private final CurrentUser currentUser;

	// --- patterns ---------------------------------------------------------------

	public record PatternResponse(String id, LocalDate validFrom, List<Integer> minutesPerWeekday,
			String holidayCalendarId) {

		static PatternResponse from(WorkingSchedule pattern) {
			return new PatternResponse(pattern.getId(), pattern.getValidFrom(), pattern.getMinutesPerWeekday(),
					pattern.getHolidayCalendarId());
		}
	}

	/**
	 * A person's patterns. {@code current} is null while the instance default applies, which is
	 * {@code defaultMinutesPerWeekday} with {@code defaultHolidayCalendarId}.
	 */
	public record ScheduleResponse(String userId, List<Integer> defaultMinutesPerWeekday,
			String defaultHolidayCalendarId, PatternResponse current, List<PatternResponse> history) {
	}

	/** A pattern from a day on, today when {@code validFrom} is absent. Seven entries, Monday first. */
	public record PatternRequest(
			LocalDate validFrom,
			@NotNull @Size(min = 7, max = 7)
			List<@NotNull @Min(0) @Max(WorkingSchedule.DAY_MINUTES_MAX) Integer> minutesPerWeekday,
			@Size(max = 64) String holidayCalendarId) {
	}

	@GetMapping("/schedule")
	public ScheduleResponse schedule(@RequestParam(required = false) String userId) {
		ScheduleService.Patterns patterns = schedules.of(currentUser.require(), userId);
		return new ScheduleResponse(patterns.userId(), patterns.defaultMinutesPerWeekday(),
				patterns.defaultCalendarId(),
				patterns.current() == null ? null : PatternResponse.from(patterns.current()),
				patterns.history().stream().map(PatternResponse::from).toList());
	}

	@PutMapping("/schedule")
	public PatternResponse saveSchedule(@RequestParam(required = false) String userId,
			@RequestBody @Valid PatternRequest request) {
		return PatternResponse.from(schedules.save(currentUser.require(), userId, request.validFrom(),
				request.minutesPerWeekday(), request.holidayCalendarId()));
	}

	@DeleteMapping("/schedule/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSchedule(@PathVariable String id) {
		schedules.delete(currentUser.require(), id);
	}

	// --- absences ---------------------------------------------------------------

	/**
	 * An absence. For a lead's view {@code id} and {@code note} are always null, and a sick day reads
	 * as {@code OTHER} ({@link AvailabilityAccess#typeFor}).
	 */
	public record TimeOffResponse(String id, String userId, TimeOff.Type type, LocalDate from, LocalDate to,
			boolean halfDay, String note) {

		static TimeOffResponse from(TimeOff item, AvailabilityAccess.Sight sight) {
			boolean full = sight == AvailabilityAccess.Sight.FULL;
			return new TimeOffResponse(full ? item.getId() : null, item.getUserId(),
					AvailabilityAccess.typeFor(item.getType(), sight), item.getFrom(), item.getTo(), item.isHalfDay(),
					full ? item.getNote() : null);
		}

		/** An absence in a capacity, which only its owner and administrators read. */
		static TimeOffResponse from(CapacityService.AbsenceMark mark, String userId) {
			return new TimeOffResponse(mark.id(), userId, mark.type(), mark.from(), mark.to(), mark.halfDay(),
					mark.note());
		}
	}

	/** A new absence; {@code userId} names somebody else, which only an administrator may. */
	public record TimeOffRequest(
			@Size(max = 64) String userId,
			@NotNull TimeOff.Type type,
			@NotNull LocalDate from,
			@NotNull LocalDate to,
			Boolean halfDay,
			@Size(max = TimeOff.NOTE_MAX) String note) {
	}

	/** An edit; what is absent is left alone, a blank note clears it. */
	public record TimeOffPatchRequest(
			TimeOff.Type type,
			LocalDate from,
			LocalDate to,
			Boolean halfDay,
			@Size(max = TimeOff.NOTE_MAX) String note) {
	}

	@GetMapping("/time-off")
	public Page<TimeOffResponse> timeOff(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String userId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		TimeOffService.Listing listing = timeOff.page(currentUser.require(), userId, from, to, page, size);
		return listing.page().map(item -> TimeOffResponse.from(item, listing.sight()));
	}

	@PostMapping("/time-off")
	@ResponseStatus(HttpStatus.CREATED)
	public TimeOffResponse createTimeOff(@RequestBody @Valid TimeOffRequest request) {
		TimeOff saved = timeOff.create(currentUser.require(), new TimeOffService.Draft(request.userId(),
				request.type(), request.from(), request.to(), request.halfDay(), request.note()));
		return TimeOffResponse.from(saved, AvailabilityAccess.Sight.FULL);
	}

	@PatchMapping("/time-off/{id}")
	public TimeOffResponse updateTimeOff(@PathVariable String id, @RequestBody @Valid TimeOffPatchRequest request) {
		TimeOff saved = timeOff.update(currentUser.require(), id, new TimeOffService.Patch(request.type(),
				request.from(), request.to(), request.halfDay(), request.note()));
		return TimeOffResponse.from(saved, AvailabilityAccess.Sight.FULL);
	}

	@DeleteMapping("/time-off/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteTimeOff(@PathVariable String id) {
		timeOff.delete(currentUser.require(), id);
	}

	// --- capacity ---------------------------------------------------------------

	public record DayResponse(LocalDate date, int scheduledMinutes, int holidayMinutes, int absenceMinutes,
			int capacityMinutes) {
	}

	public record HolidayMarkResponse(LocalDate date, String name, boolean halfDay) {
	}

	/** Capacity over a window of at most {@value CapacityService#WINDOW_DAYS_MAX} days. */
	public record CapacityResponse(String userId, LocalDate from, LocalDate to, int scheduledMinutes,
			int holidayMinutes, int absenceMinutes, int capacityMinutes, List<DayResponse> days,
			List<HolidayMarkResponse> holidays, List<TimeOffResponse> absences) {
	}

	/**
	 * One's own capacity, or anybody's for an administrator. Not a lead's to read: day by day it
	 * spells out the planned hours and the holiday calendar, which a lead does not see either.
	 */
	@GetMapping("/capacity")
	public CapacityResponse capacity(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String userId) {
		User person = access.requireKeeper(currentUser.require(), userId);
		CapacityService.Window window = capacity.window(person.getId(), from, to);
		Capacity.Result result = window.capacity();
		return new CapacityResponse(person.getId(), window.from(), window.to(), result.scheduledMinutes(),
				result.holidayMinutes(), result.absenceMinutes(), result.capacityMinutes(),
				result.days().stream().map(day -> new DayResponse(day.date(), day.scheduledMinutes(),
						day.holidayMinutes(), day.absenceMinutes(), day.capacityMinutes())).toList(),
				window.holidays().stream()
						.map(mark -> new HolidayMarkResponse(mark.date(), mark.name(), mark.halfDay())).toList(),
				window.absences().stream().map(mark -> TimeOffResponse.from(mark, person.getId())).toList());
	}
}
