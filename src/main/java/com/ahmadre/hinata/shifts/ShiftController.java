package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The routes of shift planning (HIN-43), all behind {@link ShiftPlanningGate}.
 *
 * <p>Rights per route: lists read the visible projects as {@code $in}; routes under
 * {@code /projects/{projectId}} answer 403 to a non-member; routes addressed by an event or shift
 * id answer 404 for anything out of sight and 403 to a member who does not plan the project. Every
 * write answers with the updated object, so the app replaces it instead of reloading.
 *
 * <p>The assignment routes (assign, sign up, confirm, decline, give up) and the planner role come
 * with HIN-45. Both ways onto a shift run through {@link ShiftAssignments#assign}; there is no
 * {@code DELETE} on an assignment, a freed place keeps its document until the retention run.
 *
 * <p>HIN-161: every write to an occurrence of a series (change, cancel, delete the event; add,
 * change, delete a shift) takes {@code ?scope=this|future} and answers 400
 * {@code error.shifts.seriesScopeRequired} without it. The series routes are in
 * {@link ShiftSeriesController}.
 */
@Tag(name = "Shift planning")
@RestController
@RequiredArgsConstructor
public class ShiftController {

	private final ShiftService shifts;
	private final ShiftReads reads;
	private final ShiftAssignments assignments;
	private final ShiftPlanners planners;
	private final ShiftAccess access;
	private final CurrentUser currentUser;

	// --- events ---------------------------------------------------------------

	@GetMapping("/api/v1/shifts/events")
	public Page<ShiftReads.EventSummary> events(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(required = false) String projectId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		return reads.events(currentUser.require(), from, to, projectId, page, size);
	}

	@GetMapping("/api/v1/projects/{projectId}/shifts/events")
	public Page<ShiftReads.EventSummary> projectEvents(@PathVariable String projectId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		return reads.projectEvents(currentUser.require(), projectId, from, to, page, size);
	}

	@PostMapping("/api/v1/projects/{projectId}/shifts/events")
	@ResponseStatus(HttpStatus.CREATED)
	public ShiftReads.EventResponse createEvent(@PathVariable String projectId,
			@RequestBody ShiftService.EventDraft draft) {
		User caller = currentUser.require();
		ShiftEvent event = shifts.createEvent(caller, projectId, draft);
		return detail(caller, event.getId());
	}

	@GetMapping("/api/v1/shifts/events/{id}")
	public ShiftReads.EventResponse event(@PathVariable String id) {
		return reads.event(currentUser.require(), id);
	}

	@PatchMapping("/api/v1/shifts/events/{id}")
	public ShiftReads.EventChange updateEvent(@PathVariable String id, @RequestBody ShiftService.EventPatch patch,
			@RequestParam(required = false) String scope) {
		User caller = currentUser.require();
		ShiftService.Change<ShiftEvent> change = shifts.updateEvent(caller, id, patch, scope);
		return new ShiftReads.EventChange(detail(caller, id), change.warnings());
	}

	@PostMapping("/api/v1/shifts/events/{id}/cancel")
	public ShiftReads.EventResponse cancelEvent(@PathVariable String id, @RequestParam(required = false) String scope) {
		User caller = currentUser.require();
		shifts.cancelEvent(caller, id, scope);
		return detail(caller, id);
	}

	@DeleteMapping("/api/v1/shifts/events/{id}")
	public ResponseEntity<Void> deleteEvent(@PathVariable String id, @RequestParam(required = false) String scope) {
		shifts.deleteEvent(currentUser.require(), id, scope);
		return ResponseEntity.noContent().build();
	}

	// --- shifts ---------------------------------------------------------------

	@PostMapping("/api/v1/shifts/events/{id}/shifts")
	@ResponseStatus(HttpStatus.CREATED)
	public ShiftReads.ShiftResponse createShift(@PathVariable String id, @RequestBody ShiftService.ShiftDraft draft,
			@RequestParam(required = false) String scope) {
		User caller = currentUser.require();
		Shift shift = shifts.createShift(caller, id, draft, scope);
		return reads.shiftDetail(caller, shift, project(caller, shift)).shift();
	}

	/** All days or none; a refusal names each failed day in {@code details}. */
	@PostMapping("/api/v1/shifts/events/{id}/shifts:batch")
	@ResponseStatus(HttpStatus.CREATED)
	public List<ShiftReads.ShiftResponse> createShifts(@PathVariable String id,
			@RequestBody ShiftService.BatchDraft batch, @RequestParam(required = false) String scope) {
		User caller = currentUser.require();
		List<Shift> created = shifts.createShifts(caller, id, batch, scope);
		ShiftReads.EventResponse event = detail(caller, id);
		List<String> ids = created.stream().map(Shift::getId).toList();
		return event.shifts().stream().filter(shift -> ids.contains(shift.id())).toList();
	}

	@GetMapping("/api/v1/shifts/shifts/{id}")
	public ShiftReads.ShiftDetail shift(@PathVariable String id) {
		return reads.shift(currentUser.require(), id);
	}

	@PatchMapping("/api/v1/shifts/shifts/{id}")
	public ShiftReads.ShiftChange updateShift(@PathVariable String id, @RequestBody ShiftService.ShiftPatch patch,
			@RequestParam(required = false) String scope) {
		User caller = currentUser.require();
		ShiftService.Change<Shift> change = shifts.updateShift(caller, id, patch, scope);
		Shift shift = change.item();
		return new ShiftReads.ShiftChange(reads.shiftDetail(caller, shift, project(caller, shift)).shift(),
				change.warnings());
	}

	@DeleteMapping("/api/v1/shifts/shifts/{id}")
	public ResponseEntity<Void> deleteShift(@PathVariable String id, @RequestParam(required = false) String scope) {
		shifts.deleteShift(currentUser.require(), id, scope);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/v1/shifts/calendar")
	public ShiftReads.CalendarResponse calendar(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(required = false) String projectId,
			@RequestParam(required = false) Boolean understaffed,
			@RequestParam(required = false) Boolean selfAssignable,
			@RequestParam(required = false) Integer limit) {
		return reads.calendar(currentUser.require(), from, to, projectId, understaffed, selfAssignable, limit);
	}

	// --- assignments (HIN-45) -------------------------------------------------

	/** Who a planner puts on a shift. */
	public record AssignmentDraft(String userId) {
	}

	/** A planner puts somebody on the shift ({@code ASSIGNED}); 404 unseen, 403 not a planner. */
	@PostMapping("/api/v1/shifts/shifts/{id}/assignments")
	@ResponseStatus(HttpStatus.CREATED)
	public ShiftReads.AssignmentChange assign(@PathVariable String id, @RequestBody AssignmentDraft draft) {
		User caller = currentUser.require();
		ShiftAssignments.Assigned done = assignments.assign(caller, id, draft == null ? null : draft.userId(), false);
		return reads.change(caller, done.assignment(), done.warnings());
	}

	/**
	 * A member signs up ({@code CONFIRMED}); 403 {@code selfAssignDisabled} when the event is closed.
	 * HIN-162: on a full shift, or one somebody waits for, the answer is a waiting entry
	 * ({@code WAITING}) instead.
	 */
	@PostMapping("/api/v1/shifts/shifts/{id}/self-assign")
	@ResponseStatus(HttpStatus.CREATED)
	public ShiftReads.AssignmentChange selfAssign(@PathVariable String id) {
		User caller = currentUser.require();
		ShiftAssignments.Assigned done = assignments.assign(caller, id, null, true);
		return reads.change(caller, done.assignment(), done.warnings());
	}

	/** The person confirms a planner's assignment. */
	@PostMapping("/api/v1/shifts/assignments/{id}/confirm")
	public ShiftReads.MyShiftResponse confirm(@PathVariable String id) {
		User caller = currentUser.require();
		return reads.myShift(caller, assignments.confirm(caller, id));
	}

	/** The person declines a planner's assignment; the place is free again. */
	@PostMapping("/api/v1/shifts/assignments/{id}/decline")
	public ShiftReads.AssignmentChange decline(@PathVariable String id) {
		User caller = currentUser.require();
		return reads.change(caller, assignments.decline(caller, id), List.of());
	}

	/** The person gives the shift up, or a planner takes them off it. */
	@PostMapping("/api/v1/shifts/assignments/{id}/release")
	public ShiftReads.AssignmentChange release(@PathVariable String id) {
		User caller = currentUser.require();
		return reads.change(caller, assignments.release(caller, id), List.of());
	}

	// --- planners (HIN-45) ----------------------------------------------------

	/** Who is to plan. */
	public record PlannerDraft(String userId) {
	}

	@GetMapping("/api/v1/projects/{projectId}/shifts/planners")
	public ShiftPlanners.PlannersResponse planners(@PathVariable String projectId) {
		return planners.list(currentUser.require(), projectId);
	}

	/** Leads and team admins only; the person has to see the project and be active. */
	@PostMapping("/api/v1/projects/{projectId}/shifts/planners")
	@ResponseStatus(HttpStatus.CREATED)
	public ShiftPlanners.PlannersResponse grantPlanner(@PathVariable String projectId,
			@RequestBody PlannerDraft draft) {
		return planners.grant(currentUser.require(), projectId, draft == null ? null : draft.userId());
	}

	/**
	 * Who a planner may put on a shift here: active accounts that see the project, team grants
	 * included. Planners only (403 otherwise); [q] filters by name, [limit] at most 50.
	 */
	@GetMapping("/api/v1/projects/{projectId}/shifts/candidates")
	public ShiftPlanners.CandidatesResponse candidates(@PathVariable String projectId,
			@RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
		return planners.candidates(currentUser.require(), projectId, q, limit);
	}

	@DeleteMapping("/api/v1/projects/{projectId}/shifts/planners/{userId}")
	public ResponseEntity<Void> revokePlanner(@PathVariable String projectId, @PathVariable String userId) {
		planners.revoke(currentUser.require(), projectId, userId);
		return ResponseEntity.noContent().build();
	}

	// --- the caller's own -----------------------------------------------------

	@GetMapping("/api/v1/me/shifts")
	public Page<ShiftReads.MyShiftResponse> mine(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		return reads.mine(currentUser.require(), from, to, page, size);
	}

	/** The caller's next place, or 204 when there is none. */
	@GetMapping("/api/v1/me/shifts/next")
	public ResponseEntity<ShiftReads.MyShiftResponse> next() {
		return reads.next(currentUser.require()).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** HIN-162: the caller's waiting entries, at most 50, soonest first, without pages. */
	@GetMapping("/api/v1/me/shifts/waiting")
	public List<ShiftReads.MyShiftResponse> waiting() {
		return reads.waiting(currentUser.require());
	}

	private ShiftReads.EventResponse detail(User caller, String eventId) {
		ShiftAccess.Scoped<ShiftEvent> scoped = access.visibleEvent(eventId, caller);
		return reads.detail(caller, scoped.item(), scoped.project());
	}

	private Project project(User caller, Shift shift) {
		return access.visibleShift(shift.getId(), caller).project();
	}
}
