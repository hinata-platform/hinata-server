package hn.asta.hivora.timetracking;

import hn.asta.hivora.auth.CurrentUser;
import hn.asta.hivora.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TimeTrackingController {

	private final TimeTrackingService timeTracking;
	private final WorkItemRepository workItems;
	private final CurrentUser currentUser;

	public record WorkItemRequest(
			@Min(1) @Max(1440) int durationMinutes,
			LocalDate date,
			@Size(max = 60) String activityType,
			@Size(max = 2000) String description) {
	}

	@GetMapping("/issues/{issueId}/work-items")
	public List<WorkItem> list(@PathVariable String issueId) {
		currentUser.require();
		return workItems.findByIssueIdOrderByDateDesc(issueId);
	}

	@PostMapping("/issues/{issueId}/work-items")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkItem add(@PathVariable String issueId, @RequestBody @Valid WorkItemRequest request) {
		String userId = currentUser.requireId();
		WorkItem item = WorkItem.builder()
				.userId(userId)
				.durationMinutes(request.durationMinutes())
				.date(request.date())
				.activityType(request.activityType() != null ? request.activityType() : "Development")
				.description(request.description())
				.build();
		return timeTracking.add(issueId, item);
	}

	@DeleteMapping("/work-items/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		User user = currentUser.require();
		timeTracking.delete(id, user.getId(), user.isAdmin());
	}

	@GetMapping("/timesheet")
	public List<TimeTrackingService.TimesheetRow> timesheet(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String userId,
			@RequestParam(required = false) String projectId) {
		User requester = currentUser.require();
		// Non-admins may only inspect their own timesheet unless scoped to a project.
		String effectiveUser = requester.isAdmin() ? userId
				: (projectId == null ? requester.getId() : userId);
		return timeTracking.timesheet(from, to, effectiveUser, projectId);
	}
}
