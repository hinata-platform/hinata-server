package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditLog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
	private final TimesheetApprovalService approvals;
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
			@Size(max = 20) List<@Size(max = TimeTag.MAX_NAME) String> tags,
			Boolean billable) {

		TimeTrackingService.NewEntry toDraft() {
			return new TimeTrackingService.NewEntry(projectId, issueId, durationMinutes, date,
					activityType, description, startedAt, endedAt, tags, billable);
		}
	}

	/**
	 * A window of the caller's own entries, for the calendar to draw.
	 *
	 * <p>The window is echoed back so a client can tell an answer apart from the
	 * one it asked for two navigations ago; {@code truncated} says the window
	 * held more than the server hands out, and the grid says so rather than
	 * quietly drawing a partial week.
	 *
	 * <p>The later layers this view grows — external events (stage 13), absences
	 * and holidays (stage 10) — are deliberately <em>not</em> here yet as empty
	 * arrays. An array whose element type is nothing documents nothing and pins
	 * no shape; the client reads a missing layer as an empty one, so the day
	 * those stages land they add a field and nothing else has to move.
	 */
	public record CalendarResponse(LocalDate from, LocalDate to,
			List<TimeTrackingController.WorkItemResponse> entries, boolean truncated) {

		static CalendarResponse from(TimeTrackingService.CalendarWindow window) {
			return new CalendarResponse(window.from(), window.to(),
					window.entries().stream()
							.map(TimeTrackingController.WorkItemResponse::from).toList(),
					window.truncated());
		}
	}

	/**
	 * One recorded change to an entry.
	 *
	 * <p>{@code actorLabel} is the snapshot the record was written with, so it
	 * keeps naming who acted after that account is renamed or deleted — the same
	 * rule the admin feed follows.
	 */
	public record HistoryEntryResponse(String id, Instant timestamp, String action,
			String actorId, String actorLabel, Map<String, String> metadata) {

		/**
		 * The metadata keys this screen may carry.
		 *
		 * <p>An allow-list, on the server, because this is where the boundary is.
		 * The audit metadata is a free-form map that six call sites write into and
		 * a seventh will; without this, the next one to add a key would be
		 * deciding — without knowing it — what a colleague with the
		 * {@code leadsSeeMemberEntries} policy may read about somebody's entry.
		 */
		private static final Set<String> VISIBLE = Set.of("minutes", "date", "project", "issue");

		static HistoryEntryResponse from(AuditLog log) {
			Map<String, String> meta = log.getMetadata() == null ? Map.of()
					: log.getMetadata().entrySet().stream()
							.filter(entry -> VISIBLE.contains(entry.getKey()))
							.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
									(first, second) -> first, LinkedHashMap::new));
			return new HistoryEntryResponse(log.getId(), log.getTimestamp(),
					log.getAction() == null ? null : log.getAction().name(),
					log.getActorId(), log.getActorLabel(), meta);
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

	/**
	 * The same entries arranged as a window instead of a page — what the calendar
	 * reads.
	 *
	 * <p>A grid cannot use a page: it places the whole window or places nothing.
	 * So this route answers with everything in the window at once, and pays for
	 * that with two bounds — a month wide at most, and
	 * {@link TimeTrackingService#CALENDAR_CAP} entries at most.
	 */
	@GetMapping("/calendar")
	public CalendarResponse calendar(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return CalendarResponse.from(timeTracking.calendar(from, to, currentUser.require()));
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

	/**
	 * What has been recorded about this entry, newest first.
	 *
	 * <p>Deliberately narrower than the admin audit feed it reads from: the
	 * action, when, who, and the fields that moved. No client address and no
	 * user-agent — those are in the record for a security investigation an
	 * administrator runs, and putting them on a screen every colleague can open
	 * would turn a transparency feature into a way of finding out where somebody
	 * was working from.
	 */
	@GetMapping("/entries/{id}/history")
	public Page<HistoryEntryResponse> history(@PathVariable String id,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return timeTracking.history(id, page, size, currentUser.require())
				.map(HistoryEntryResponse::from);
	}

	/**
	 * Asks for a frozen entry of one's own to be opened.
	 *
	 * <p>Art. 16 DSGVO: inaccurate personal data has to be correctable without
	 * undue delay, and working time is personal data. A freeze with no way to ask
	 * would be the one thing that right does not allow — so this route exists, it
	 * reaches whoever can actually lift the freeze (an administrator for the lock
	 * date, the project's approvers for a submitted period), and it is recorded.
	 *
	 * <p>It changes nothing on its own, deliberately. A request that could unfreeze
	 * anything by being made would be the freeze with an extra step in front of it.
	 */
	@PostMapping("/entries/{id}/correction-request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void requestCorrection(@PathVariable String id,
			@RequestBody @Valid TimesheetApprovalController.NoteRequest request) {
		approvals.requestCorrection(id, request.getNote(), currentUser.require());
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
