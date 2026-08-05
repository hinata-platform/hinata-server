package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.moderation.report.ContentReport;
import com.ahmadre.hinata.moderation.report.ContentReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The admin moderation queue: what the automated gate flagged, what users
 * reported, and the two calls that take a row out of either list.
 *
 * <p>Admin-only, the same way every other {@code /api/v1/admin/**} surface is —
 * the ADMIN rule in {@code SecurityConfig} plus the explicit {@code @PreAuthorize}
 * that {@link com.ahmadre.hinata.admin.AdminUserController} and
 * {@link com.ahmadre.hinata.audit.AuditController} carry. The annotation is not
 * redundant: it keeps the guarantee on the class rather than in a path pattern
 * three packages away, so moving or renaming a route cannot silently unprotect it.
 *
 * <p>This is the endpoint the store requirement actually rests on. Reporting and
 * blocking are what a user can do (see
 * {@link com.ahmadre.hinata.moderation.report.ContentReportController}); Apple
 * Guideline 1.2 and the Play UGC policy also require that somebody acts on those
 * reports, and an action nobody can take is not a moderation process. Everything
 * here is therefore read plus one decision — no bulk deletion, no automatic
 * removal on a count of reports, nothing that lets moderation be aimed.
 */
@Tag(name = "Admin · Moderation")
@RestController
@RequestMapping("/api/v1/admin/moderation")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminModerationController {

	private final ModerationQueueService queue;
	private final CurrentUser currentUser;

	// --- DTOs -----------------------------------------------------------------

	/**
	 * One page of a queue.
	 *
	 * <p>{@code items} + {@code total} rather than Spring's own {@code Page}
	 * serialisation, matching {@code AuditPageResponse} and
	 * {@code AdminUserListResponse}: the client's infinite-scrolling list needs the
	 * full count to know whether another page exists, and a {@code Page} on the wire
	 * ships a dozen fields of pagination bookkeeping that then become an accidental
	 * API contract.
	 *
	 * @param page zero-based, unlike the audit and user boards — those endpoints
	 *             predate the shared client-side pager, which counts from zero
	 */
	public record ModerationPageResponse<T>(List<T> items, long total, int page, int size) {

		static <T> ModerationPageResponse<T> of(Page<T> found) {
			return new ModerationPageResponse<>(found.getContent(), found.getTotalElements(),
					found.getNumber(), found.getSize());
		}
	}

	/**
	 * A moderator's decision on a recorded verdict.
	 *
	 * @param note the moderator's reasoning. Stored, never shown to the author — it is
	 *             what an appeal is answered from months later, when nobody remembers
	 *             the item
	 */
	public record ReviewRequest(@NotNull ModerationRecord.ReviewState state,
			@Size(max = ContentReportService.MAX_NOTE_LENGTH) String note) {
	}

	/** A moderator's decision on a user's report. */
	public record HandleRequest(@NotNull ContentReport.State state,
			@Size(max = ContentReportService.MAX_NOTE_LENGTH) String note) {
	}

	// --- Recorded verdicts -----------------------------------------------------

	/**
	 * Every filter is optional and {@code null} widens — including {@code state}, so a
	 * moderator can look back at what was already decided rather than only at the open
	 * backlog.
	 */
	@Operation(summary = "Paginated queue of recorded moderation verdicts")
	@GetMapping("/records")
	public ModerationPageResponse<ModerationQueueService.RecordRow> records(
			@RequestParam(required = false) ModerationRecord.ReviewState state,
			@RequestParam(required = false) ModerationSurface surface,
			@RequestParam(required = false) ModerationCategory category,
			@RequestParam(required = false) String projectId,
			@RequestParam(required = false) String authorId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return ModerationPageResponse.of(
				queue.listRecords(state, surface, category, projectId, authorId, page, size));
	}

	/**
	 * Returns the decided row rather than 204, so the list can replace it in place.
	 * A queue that has to refetch a page to show the outcome of a decision loses the
	 * moderator's scroll position on every single item they judge.
	 */
	@Operation(summary = "Confirm or dismiss a recorded verdict")
	@PostMapping("/records/{id}/review")
	public ModerationQueueService.RecordRow review(@PathVariable String id,
			@RequestBody @Valid ReviewRequest request) {
		return queue.review(currentUser.require(), id, request.state(), request.note());
	}

	// --- User reports ----------------------------------------------------------

	@Operation(summary = "Paginated queue of user-submitted content reports")
	@GetMapping("/reports")
	public ModerationPageResponse<ModerationQueueService.ReportRow> reports(
			@RequestParam(required = false) ContentReport.State state,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return ModerationPageResponse.of(queue.listReports(state, page, size));
	}

	@Operation(summary = "Uphold or dismiss a user report")
	@PostMapping("/reports/{id}/handle")
	public ModerationQueueService.ReportRow handle(@PathVariable String id,
			@RequestBody @Valid HandleRequest request) {
		return queue.handleReport(currentUser.require(), id, request.state(), request.note());
	}

	// --- Backlog ---------------------------------------------------------------

	/**
	 * Two counts, for the badge on the admin section. Its own endpoint because it is
	 * fetched when the admin area opens, long before either queue is: a badge that
	 * costs a full page of rows plus their cross-collection lookups would be paid for
	 * by every admin who never opens moderation at all.
	 */
	@Operation(summary = "Open counts for both moderation queues")
	@GetMapping("/summary")
	public ModerationQueueService.Summary summary() {
		return queue.summary();
	}
}
