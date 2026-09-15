package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Holiday calendars and their days over HTTP.
 *
 * <p>Reading is open to every signed-in person: the pattern editor offers the calendars, and the
 * calendar view shows the days. Everything else is an administrator's, and so is everything about
 * a feed: whether a calendar has one, its host, and how its imports went.
 */
@Tag(name = "Availability")
@RestController
@RequestMapping("/api/v1/availability/holidays")
@RequiredArgsConstructor
public class HolidayController {

	private final HolidayService holidays;
	private final CurrentUser currentUser;
	private final UserWords words;

	/**
	 * A calendar. The feed and import fields are null for anybody but an administrator;
	 * {@code lastImportError} is the sentence in the reader's language.
	 */
	public record CalendarResponse(String id, String name, String region, boolean defaultCalendar, Boolean hasFeed,
			String feedHost, HolidayCalendar.ImportState importState, Instant lastImportedAt, String lastImportError,
			HolidayCalendar.ImportSummary lastImport) {

		static CalendarResponse from(HolidayCalendar calendar, boolean admin, UserWords words) {
			if (!admin) {
				return new CalendarResponse(calendar.getId(), calendar.getName(), calendar.getRegion(),
						calendar.isDefaultCalendar(), null, null, null, null, null, null);
			}
			String error = calendar.getLastImportError();
			String sentence = error == null ? null
					: calendar.getLastImportErrorArg() == null
							? words.in(LocaleContextHolder.getLocale(), error)
							: words.in(LocaleContextHolder.getLocale(), error, calendar.getLastImportErrorArg());
			return new CalendarResponse(calendar.getId(), calendar.getName(), calendar.getRegion(),
					calendar.isDefaultCalendar(), calendar.getSource() != null, calendar.getSourceHost(),
					calendar.getImportState(), calendar.getLastImportedAt(), sentence, calendar.getLastImport());
		}
	}

	public record CalendarRequest(
			@NotBlank @Size(max = HolidayCalendar.NAME_MAX) String name,
			@Size(max = HolidayCalendar.REGION_MAX) String region,
			@Size(max = 2048) String icsUrl,
			Boolean defaultCalendar) {
	}

	/** An edit; absent fields are left alone, a blank {@code icsUrl} removes the feed. */
	public record CalendarPatchRequest(
			@Size(max = HolidayCalendar.NAME_MAX) String name,
			@Size(max = HolidayCalendar.REGION_MAX) String region,
			@Size(max = 2048) String icsUrl,
			Boolean defaultCalendar) {
	}

	public record HolidayResponse(String id, String calendarId, LocalDate date, String name, boolean halfDay,
			Holiday.Source source) {

		static HolidayResponse from(Holiday holiday) {
			return new HolidayResponse(holiday.getId(), holiday.getCalendarId(), holiday.getDate(), holiday.getName(),
					holiday.isHalfDay(), holiday.getSource());
		}
	}

	public record HolidayRequest(
			@NotBlank @Size(max = 64) String calendarId,
			@NotNull LocalDate date,
			@NotBlank @Size(max = Holiday.NAME_MAX) String name,
			Boolean halfDay) {
	}

	public record HolidayPatchRequest(
			LocalDate date,
			@Size(max = Holiday.NAME_MAX) String name,
			Boolean halfDay) {
	}

	// --- calendars ------------------------------------------------------------

	@GetMapping("/calendars")
	public Page<CalendarResponse> calendars(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		boolean admin = currentUser.require().isAdmin();
		return holidays.calendars(page, size).map(calendar -> CalendarResponse.from(calendar, admin, words));
	}

	@PostMapping("/calendars")
	@ResponseStatus(HttpStatus.CREATED)
	public CalendarResponse createCalendar(@RequestBody @Valid CalendarRequest request) {
		User admin = currentUser.require();
		return CalendarResponse.from(holidays.createCalendar(admin, new HolidayService.CalendarDraft(request.name(),
				request.region(), request.icsUrl(), request.defaultCalendar())), true, words);
	}

	@PatchMapping("/calendars/{id}")
	public CalendarResponse updateCalendar(@PathVariable String id, @RequestBody @Valid CalendarPatchRequest request) {
		User admin = currentUser.require();
		return CalendarResponse.from(holidays.updateCalendar(admin, id, new HolidayService.CalendarPatch(
				request.name(), request.region(), request.icsUrl(), request.defaultCalendar())), true, words);
	}

	@DeleteMapping("/calendars/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteCalendar(@PathVariable String id) {
		holidays.deleteCalendar(currentUser.require(), id);
	}

	/**
	 * Starts importing a year of the calendar's feed and answers at once, with the calendar in the
	 * state {@code RUNNING}. Reading the calendar again shows how it went.
	 */
	@PostMapping("/calendars/{id}/import")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public CalendarResponse importHolidays(@PathVariable String id, @RequestParam(required = false) Integer year) {
		User admin = currentUser.require();
		holidays.importYear(admin, id, year);
		return CalendarResponse.from(holidays.requireCalendar(id), true, words);
	}

	// --- days -----------------------------------------------------------------

	/** One calendar's holidays in a year, the current year when absent. */
	@GetMapping
	public List<HolidayResponse> holidays(@RequestParam String calendarId,
			@RequestParam(required = false) Integer year) {
		currentUser.require();
		return holidays.holidaysOf(calendarId, year).stream().map(HolidayResponse::from).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public HolidayResponse addHoliday(@RequestBody @Valid HolidayRequest request) {
		return HolidayResponse.from(holidays.addHoliday(currentUser.require(), new HolidayService.HolidayDraft(
				request.calendarId(), request.date(), request.name(), request.halfDay())));
	}

	@PatchMapping("/{id}")
	public HolidayResponse updateHoliday(@PathVariable String id, @RequestBody @Valid HolidayPatchRequest request) {
		return HolidayResponse.from(holidays.updateHoliday(currentUser.require(), id,
				new HolidayService.HolidayPatch(request.date(), request.name(), request.halfDay())));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteHoliday(@PathVariable String id) {
		holidays.deleteHoliday(currentUser.require(), id);
	}
}
