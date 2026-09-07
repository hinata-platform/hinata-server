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
import java.util.List;

/**
 * The extended module's own entries: a person's list of what they worked on,
 * and the way to add to it without going through an issue first.
 *
 * <p>Everything here sits behind {@link AdvancedTimeTrackingGate}, so with the
 * module off these routes answer 404 with {@code error.feature.disabled}. The
 * 1.x routes in {@link TimeTrackingController} stay open in either case and
 * keep their shapes — the published app talks to those.
 *
 * <p>Editing and deleting go through the same service methods as
 * {@code /work-items/{id}} rather than getting their own rules. There is one
 * definition of who may change an entry, and this controller does not get to
 * hold a second opinion.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time")
@RequiredArgsConstructor
public class TimeEntryController {

	private final TimeTrackingService timeTracking;
	private final TimerService timers;
	private final CurrentUser currentUser;

	// --- DTOs -----------------------------------------------------------------

	/**
	 * An entry plus what was noticed about it while saving.
	 *
	 * <p>{@code overlaps} names the caller's other entries that share time with
	 * this one. It is advice, not a verdict: two entries may legitimately overlap
	 * (a call taken during other work), so the server records what it was told
	 * and says what it noticed, and the person decides. A wrapper rather than a
	 * field on the entry, because it is a property of this save — re-reading the
	 * list later would answer it differently as the day fills up.
	 */
	public record TimeEntryResponse(TimeTrackingController.WorkItemResponse entry,
			List<String> overlaps) {
	}

	/**
	 * A new entry. Either both instants or {@code durationMinutes} must be
	 * present; with the pair, the duration and (absent {@code date}) the day come
	 * from it.
	 */
	public record TimeEntryRequest(
			String projectId,
			String issueId,
			@Min(1) @Max(TimeTrackingService.MAX_MINUTES) Integer durationMinutes,
			LocalDate date,
			@Size(max = 60) String activityType,
			@Size(max = 2000) String description,
			Instant startedAt,
			Instant endedAt,
			@Size(max = 20) List<@Size(max = 40) String> tags,
			Boolean billable) {

		TimeTrackingService.NewEntry toDraft() {
			return new TimeTrackingService.NewEntry(projectId, issueId, durationMinutes, date,
					activityType, description, startedAt, endedAt, tags, billable);
		}
	}

	// --- the list --------------------------------------------------------------

	/**
	 * The caller's own entries, newest first.
	 *
	 * <p>Own with no way to ask otherwise — see
	 * {@link TimeTrackingService#entries}. The filters narrow; none of them
	 * widens.
	 */
	@GetMapping("/entries")
	public Page<TimeTrackingController.WorkItemResponse> entries(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String projectId,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		User user = currentUser.require();
		return timeTracking
				.entries(new TimeTrackingService.EntryFilter(from, to, projectId, q), page, size, user)
				.map(TimeTrackingController.WorkItemResponse::from);
	}

	// --- one entry -------------------------------------------------------------

	@PostMapping("/entries")
	@ResponseStatus(HttpStatus.CREATED)
	public TimeEntryResponse create(@RequestBody @Valid TimeEntryRequest request) {
		User user = currentUser.require();
		return respond(timeTracking.create(request.toDraft(), WorkItem.Source.APP, user), user);
	}

	@PatchMapping("/entries/{id}")
	public TimeEntryResponse update(@PathVariable String id,
			@RequestBody @Valid TimeTrackingController.WorkItemPatchRequest request) {
		User user = currentUser.require();
		return respond(timeTracking.update(id, request.toPatch(), user), user);
	}

	@DeleteMapping("/entries/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		timeTracking.delete(id, currentUser.require());
	}

	/** Starts a timer carrying this entry's description and placement. */
	@PostMapping("/entries/{id}/continue")
	@ResponseStatus(HttpStatus.CREATED)
	public TimerController.TimerResponse continueEntry(@PathVariable String id) {
		return TimerController.TimerResponse.from(
				timers.continueFrom(id, currentUser.require()));
	}

	private TimeEntryResponse respond(WorkItem item, User actor) {
		return new TimeEntryResponse(TimeTrackingController.WorkItemResponse.from(item),
				timeTracking.overlapsOf(item, actor));
	}
}
