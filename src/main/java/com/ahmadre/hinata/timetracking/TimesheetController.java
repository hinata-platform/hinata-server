package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The timesheet matrix, paged — the module's own view of the same rows the
 * frozen {@code GET /api/v1/timesheet} hands out as one array.
 *
 * <p>Its own controller rather than another method on {@link TimeEntryController}
 * because the two draw a line in a different place. Everything there answers
 * with the caller's own entries and offers no way to ask otherwise; this route
 * takes a {@code userId} and may, for an admin, answer about somebody else. That
 * is the boundary worth being able to see at a glance when reading who may read
 * what — so it gets its own file, and the rule itself stays in the service where
 * both routes share it.
 *
 * <p>Behind {@link AdvancedTimeTrackingGate} like the rest of {@code /api/v1/time}.
 * The frozen route stays open and keeps its shape either way: the published app
 * reads that one.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time")
@RequiredArgsConstructor
public class TimesheetController {

	private final TimeTrackingService timeTracking;
	private final CurrentUser currentUser;

	/**
	 * One page of rows, ordered by user and then project.
	 *
	 * <p>A row is a person and a project; its columns are the days of the window.
	 * Non-admins get their own rows and nothing else — naming somebody else is a
	 * 403, not a quietly narrowed answer.
	 */
	@GetMapping("/timesheet")
	public Page<TimeTrackingService.TimesheetRow> timesheet(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String userId,
			@RequestParam(required = false) String projectId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return timeTracking.timesheetPage(from, to, userId, projectId, page, size,
				currentUser.require());
	}
}
