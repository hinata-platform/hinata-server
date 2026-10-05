package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What the caller does with the events of their own subscriptions (HIN-94): take one over as an
 * entry, and learn how many of today's are still open. The events themselves arrive with the
 * calendar window ({@code GET /api/v1/time/calendar}).
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/calendar/events")
@RequiredArgsConstructor
public class CalendarEventController {

	private final CalendarSubscriptionService subscriptions;
	private final CurrentUser currentUser;

	public record ConvertRequest(@Size(max = 64) String projectId, @Size(max = 64) String issueId,
			@Size(max = 20) List<@Size(max = TimeTag.MAX_NAME) String> tags, Boolean billable,
			@Size(max = 2000) String description) {
	}

	public record OpenTodayResponse(int count) {
	}

	/**
	 * Files the event as an entry, with its own start and end. Idempotent: a second call answers
	 * with the entry the first made. 404 for an event that is not the caller's.
	 */
	@PostMapping("/{id}/convert")
	public TimeTrackingController.WorkItemResponse convert(@PathVariable String id,
			@Valid @RequestBody(required = false) ConvertRequest body) {
		ConvertRequest request = body == null ? new ConvertRequest(null, null, null, null, null) : body;
		return TimeTrackingController.WorkItemResponse.from(subscriptions.convert(currentUser.require(), id,
				new CalendarSubscriptionService.Conversion(request.projectId(), request.issueId(), request.tags(),
						request.billable(), request.description())));
	}

	/** Today's events that are over and not taken over yet; 0 while the import is switched off. */
	@GetMapping("/open-today")
	public OpenTodayResponse openToday() {
		return new OpenTodayResponse(subscriptions.openToday(currentUser.require()));
	}
}
