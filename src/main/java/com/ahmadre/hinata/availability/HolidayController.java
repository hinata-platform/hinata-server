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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.time.Duration;
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
	private final HolidayRules rules;
	private final CurrentUser currentUser;
	private final UserWords words;

	/**
	 * A calendar. The feed and import fields are null for anybody but an administrator;
	 * {@code lastImportError} is the sentence in the reader's language. {@code rules} is the region
	 * whose statutory holidays fill it, {@code rulesName} that region in the reader's language, and
	 * {@code platformDefault} says the platform made it and keeps it on the platform's region.
	 */
	public record CalendarResponse(String id, String name, String region, boolean defaultCalendar, Boolean hasFeed,
			String feedHost, HolidayCalendar.ImportState importState, Instant lastImportedAt, String lastImportError,
			HolidayCalendar.ImportSummary lastImport, String rules, String rulesName, boolean platformDefault) {

		static CalendarResponse from(HolidayCalendar calendar, boolean admin, UserWords words, HolidayRules rules) {
			String code = calendar.getRules();
			String rulesName = code == null ? null : rules.nameOf(code, LocaleContextHolder.getLocale());
			if (!admin) {
				return new CalendarResponse(calendar.getId(), calendar.getName(), calendar.getRegion(),
						calendar.isDefaultCalendar(), null, null, null, null, null, null, code, rulesName,
						calendar.isPlatformDefault());
			}
			String error = calendar.getLastImportError();
			String sentence = error == null ? null
					: calendar.getLastImportErrorArg() == null
							? words.in(LocaleContextHolder.getLocale(), error)
							: words.in(LocaleContextHolder.getLocale(), error, calendar.getLastImportErrorArg());
			return new CalendarResponse(calendar.getId(), calendar.getName(), calendar.getRegion(),
					calendar.isDefaultCalendar(), calendar.getSource() != null, calendar.getSourceHost(),
					calendar.getImportState(), calendar.getLastImportedAt(), sentence, calendar.getLastImport(), code,
					rulesName, calendar.isPlatformDefault());
		}
	}

	/** A new calendar; {@code rules} ({@code DE-BY}) and {@code icsUrl} exclude each other. */
	public record CalendarRequest(
			@NotBlank @Size(max = HolidayCalendar.NAME_MAX) String name,
			@Size(max = HolidayCalendar.REGION_MAX) String region,
			@Size(max = 2048) String icsUrl,
			@Size(max = 16) String rules,
			Boolean defaultCalendar) {
	}

	/**
	 * An edit; absent fields are left alone, a blank {@code icsUrl} removes the feed and blank
	 * {@code rules} the rules.
	 */
	public record CalendarPatchRequest(
			@Size(max = HolidayCalendar.NAME_MAX) String name,
			@Size(max = HolidayCalendar.REGION_MAX) String region,
			@Size(max = 2048) String icsUrl,
			@Size(max = 16) String rules,
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

	/**
	 * The regions a calendar can follow, countries by name in the reader's language, each with its
	 * first level of regions. The same for everybody and the same until the server is updated.
	 */
	@GetMapping("/regions")
	public ResponseEntity<List<HolidayRules.Region>> regions() {
		currentUser.require();
		// About 160 KB that only an update changes: a browser keeps it for the day.
		return ResponseEntity.ok()
				.cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
				.varyBy(HttpHeaders.ACCEPT_LANGUAGE)
				.body(rules.regions(LocaleContextHolder.getLocale()));
	}

	@GetMapping("/calendars")
	public Page<CalendarResponse> calendars(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		boolean orgAdmin = currentUser.require().isOrgAdmin();
		return holidays.calendars(page, size).map(calendar -> CalendarResponse.from(calendar, orgAdmin, words, rules));
	}

	@PostMapping("/calendars")
	@ResponseStatus(HttpStatus.CREATED)
	public CalendarResponse createCalendar(@RequestBody @Valid CalendarRequest request) {
		User admin = currentUser.require();
		return CalendarResponse.from(holidays.createCalendar(admin, new HolidayService.CalendarDraft(request.name(),
				request.region(), request.icsUrl(), request.rules(), request.defaultCalendar())), true, words, rules);
	}

	@PatchMapping("/calendars/{id}")
	public CalendarResponse updateCalendar(@PathVariable String id, @RequestBody @Valid CalendarPatchRequest request) {
		User admin = currentUser.require();
		return CalendarResponse.from(holidays.updateCalendar(admin, id, new HolidayService.CalendarPatch(
				request.name(), request.region(), request.icsUrl(), request.rules(), request.defaultCalendar())), true, words, rules);
	}

	@DeleteMapping("/calendars/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteCalendar(@PathVariable String id) {
		holidays.deleteCalendar(currentUser.require(), id);
	}

	/**
	 * Starts importing a year of the calendar's feed and answers at once, with the calendar in the
	 * state {@code RUNNING}. Reading the calendar again shows how it went. A calendar with rules
	 * fills the year at once, and adds back days that were removed from it.
	 */
	@PostMapping("/calendars/{id}/import")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public CalendarResponse importHolidays(@PathVariable String id, @RequestParam(required = false) Integer year) {
		User admin = currentUser.require();
		holidays.importYear(admin, id, year);
		return CalendarResponse.from(holidays.requireCalendar(id), true, words, rules);
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
