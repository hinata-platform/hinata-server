package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The team absence calendar and the capacity band beside it (HIN-118).
 *
 * <p>Behind {@link AbsenceManagementGate} like everything under {@code /api/v1/time-off}, and
 * behind one more switch the gate does not know: with {@code absenceCalendarVisibility} at
 * {@code OFF} both routes answer exactly as if the module were off. Who may read which group and
 * how coarsely is {@link TeamAbsenceService}'s question, not this one's.
 */
@Tag(name = "Absence management")
@RestController
@RequestMapping("/api/v1/time-off")
@RequiredArgsConstructor
public class TeamAbsenceController {

	private final TeamAbsenceService calendars;
	private final CurrentUser currentUser;

	/**
	 * Rows of people and their absences from [from] to [to], a quarter at most. With neither
	 * [teamId] nor [projectId] the group is the reader's own projects.
	 */
	@GetMapping("/calendar")
	public TeamAbsenceService.CalendarPage calendar(@RequestParam LocalDate from, @RequestParam LocalDate to,
			@RequestParam(required = false) String teamId, @RequestParam(required = false) String projectId,
			@RequestParam(defaultValue = "false") boolean awayOnly,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return calendars.calendar(currentUser.require(), teamId, projectId, from, to, awayOnly, page, size);
	}

	/** What the group has left per day (a quarter at most) or per week (a year at most). */
	@GetMapping("/capacity-band")
	public TeamAbsenceService.Band band(@RequestParam LocalDate from, @RequestParam LocalDate to,
			@RequestParam(required = false) String teamId, @RequestParam(required = false) String projectId,
			@RequestParam(defaultValue = "DAY") TeamAbsenceService.Resolution resolution) {
		return calendars.band(currentUser.require(), teamId, projectId, from, to, resolution);
	}
}
