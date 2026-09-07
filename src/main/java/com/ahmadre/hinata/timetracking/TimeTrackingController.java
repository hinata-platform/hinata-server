package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TimeTrackingController {

	private final TimeTrackingService timeTracking;
	private final CurrentUser currentUser;

	// --- DTOs -----------------------------------------------------------------

	/**
	 * A logged entry on the wire. Mirrors the entity field for field — the
	 * published app parses today's shape, so nothing here is renamed; the 2.0
	 * fields are additive and nullable.
	 */
	public record WorkItemResponse(String id, String issueId, String projectId, String userId,
			LocalDate date, int durationMinutes, String activityType, String description,
			Instant createdAt, Instant startedAt, Instant endedAt, boolean billable,
			List<String> tags, WorkItem.Source source, Instant updatedAt, String updatedBy,
			String sharedFromId) {

		public static WorkItemResponse from(WorkItem item) {
			return new WorkItemResponse(item.getId(), item.getIssueId(), item.getProjectId(),
					item.getUserId(), item.getDate(), item.getDurationMinutes(),
					item.getActivityType(), item.getDescription(), item.getCreatedAt(),
					item.getStartedAt(), item.getEndedAt(), item.isBillable(), item.getTags(),
					item.getSource(), item.getUpdatedAt(), item.getUpdatedBy(),
					item.getSharedFromId());
		}
	}

	/**
	 * Logging time. {@code durationMinutes} may be left out when both instants
	 * are given — the pair then defines it, and the day when {@code date} is
	 * absent too.
	 */
	public record WorkItemRequest(
			@Min(1) @Max(TimeTrackingService.MAX_MINUTES) Integer durationMinutes,
			LocalDate date,
			@Size(max = 60) String activityType,
			@Size(max = 2000) String description,
			Instant startedAt,
			Instant endedAt,
			@Size(max = 20) List<@Size(max = 40) String> tags,
			Boolean billable) {

		TimeTrackingService.NewWorkItem toDraft() {
			return new TimeTrackingService.NewWorkItem(durationMinutes, date, activityType,
					description, startedAt, endedAt, tags, billable);
		}
	}

	/**
	 * A partial edit: every field absent from the body is left alone. A class
	 * with setters rather than a record, on purpose — Jackson only calls a
	 * setter for a property that is present, which is how an absent
	 * {@code startedAt} (keep) is told apart from an explicit null (clear).
	 * A record cannot make that distinction.
	 */
	public static final class WorkItemPatchRequest {

		private final Set<String> present = new HashSet<>();

		@Min(1) @Max(TimeTrackingService.MAX_MINUTES)
		private Integer durationMinutes;
		private LocalDate date;
		@Size(max = 60)
		private String activityType;
		@Size(max = 2000)
		private String description;
		private Instant startedAt;
		private Instant endedAt;
		@Size(max = 20)
		private List<@Size(max = 40) String> tags;
		private Boolean billable;

		public void setDurationMinutes(Integer durationMinutes) {
			this.durationMinutes = durationMinutes;
			present.add("durationMinutes");
		}

		public void setDate(LocalDate date) {
			this.date = date;
			present.add("date");
		}

		public void setActivityType(String activityType) {
			this.activityType = activityType;
			present.add("activityType");
		}

		public void setDescription(String description) {
			this.description = description;
			present.add("description");
		}

		public void setStartedAt(Instant startedAt) {
			this.startedAt = startedAt;
			present.add("startedAt");
		}

		public void setEndedAt(Instant endedAt) {
			this.endedAt = endedAt;
			present.add("endedAt");
		}

		public void setTags(List<String> tags) {
			this.tags = tags;
			present.add("tags");
		}

		public void setBillable(Boolean billable) {
			this.billable = billable;
			present.add("billable");
		}

		public Integer getDurationMinutes() {
			return durationMinutes;
		}

		public LocalDate getDate() {
			return date;
		}

		public String getActivityType() {
			return activityType;
		}

		public String getDescription() {
			return description;
		}

		public Instant getStartedAt() {
			return startedAt;
		}

		public Instant getEndedAt() {
			return endedAt;
		}

		public List<String> getTags() {
			return tags;
		}

		public Boolean getBillable() {
			return billable;
		}

		/** Whether the body named this property at all (with any value, null included). */
		public boolean has(String property) {
			return present.contains(property);
		}

		TimeTrackingService.WorkItemPatch toPatch() {
			return new TimeTrackingService.WorkItemPatch(durationMinutes, date, activityType,
					description, has("startedAt"), startedAt, has("endedAt"), endedAt, tags, billable);
		}
	}

	// --- per issue ---------------------------------------------------------------

	/** An issue's entries, newest first — an array as before, now capped at the 200 newest. */
	@GetMapping("/issues/{issueId}/work-items")
	public List<WorkItemResponse> list(@PathVariable String issueId) {
		User user = currentUser.require();
		return timeTracking.list(issueId, user).stream().map(WorkItemResponse::from).toList();
	}

	/** The same entries, paged (newest first, at most 100 per page). */
	@GetMapping("/issues/{issueId}/work-items/page")
	public Page<WorkItemResponse> page(@PathVariable String issueId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		User user = currentUser.require();
		return timeTracking.page(issueId, page, size, user).map(WorkItemResponse::from);
	}

	@PostMapping("/issues/{issueId}/work-items")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkItemResponse add(@PathVariable String issueId, @RequestBody @Valid WorkItemRequest request) {
		User user = currentUser.require();
		return WorkItemResponse.from(
				timeTracking.add(issueId, request.toDraft(), WorkItem.Source.APP, user));
	}

	// --- per entry ---------------------------------------------------------------

	@PatchMapping("/work-items/{id}")
	public WorkItemResponse update(@PathVariable String id,
			@RequestBody @Valid WorkItemPatchRequest request) {
		User user = currentUser.require();
		return WorkItemResponse.from(timeTracking.update(id, request.toPatch(), user));
	}

	@DeleteMapping("/work-items/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		timeTracking.delete(id, currentUser.require());
	}

	// --- timesheet ---------------------------------------------------------------

	@GetMapping("/timesheet")
	public List<TimeTrackingService.TimesheetRow> timesheet(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String userId,
			@RequestParam(required = false) String projectId) {
		// The scoping rule (own rows unless admin, visible projects only) lives
		// in the service so MCP and REST cannot drift apart.
		return timeTracking.timesheet(from, to, userId, projectId, currentUser.require());
	}
}
