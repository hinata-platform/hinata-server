package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.moderation.freeze.FrozenContent;
import com.ahmadre.hinata.moderation.freeze.FrozenContentService;
import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
import com.ahmadre.hinata.moderation.report.ContentReport;
import com.ahmadre.hinata.moderation.report.ContentReportService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
	private final FrozenContentService frozen;
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

	// --- Freeze ----------------------------------------------------------------

	/**
	 * A hand-raised freeze, or its release.
	 *
	 * @param note why. Optional on a freeze — the trigger is self-evident from the
	 *             report it accompanies — and <b>required</b> on an unfreeze, which
	 *             is enforced in the service rather than here because that is where
	 *             the automatic triggers also arrive
	 */
	public record FreezeRequest(@NotNull FrozenTargetType targetType, @NotNull String targetId,
			String contextId, @Size(max = ContentReportService.MAX_NOTE_LENGTH) String note) {
	}

	/**
	 * What a freeze looks like to an admin.
	 *
	 * <p>Deliberately no label, no link and no object keys. This endpoint answers
	 * "is this frozen, since when, on whose report" — the questions an operator has
	 * to answer to an authority — and nothing that would let the caller find the
	 * content. A DTO that echoed the storage keys would hand back exactly the
	 * addressing the byte guard exists to refuse.
	 */
	public record FreezeResponse(String id, FrozenTargetType targetType, String targetId,
			ModerationCategory category, String reportId, String reporterId, Instant frozenAt,
			Instant statementIssuedAt) {

		static FreezeResponse of(FrozenContent row) {
			return new FreezeResponse(row.getId(), row.getTargetType(), row.getTargetId(),
					row.getCategory(), row.getReportId(), row.getReporterId(), row.getFrozenAt(),
					row.getStatementIssuedAt());
		}
	}

	/**
	 * Freezes a target by hand — the path for content an authority named directly,
	 * or that a moderator recognised from a report on something else.
	 *
	 * <p>The empty object-key list is not a shortcut and used to be a hole. It said
	 * "this freeze covers no stored bytes", which made a hand-freeze of an
	 * {@code ATTACHMENT} or a {@code USER} a complete no-op and left every attachment
	 * of a hand-frozen issue downloadable by anyone holding the URL. The keys are now
	 * resolved inside {@code FrozenContentService.freeze}, from the target, by the
	 * same {@code FrozenObjectKeys} the report-triggered path uses — so the two ways
	 * a freeze is raised cover exactly the same bytes, and neither can be given an
	 * incomplete list by a caller.
	 *
	 * <p>{@code contextId} still matters and is still the caller's to supply: an
	 * attachment is a subdocument of its issue and cannot be located by id alone.
	 * <b>For an {@code ATTACHMENT} it is required, and its absence is a 400 rather
	 * than a warning.</b> {@code FrozenObjectKeys.ofAttachment} cannot find the file
	 * without it, so the freeze would write a row, return 200, appear in the queue and
	 * protect not one byte — the exact "wired on paper, dead in practice" shape this
	 * endpoint was fixed for once already. A refusal the operator can act on beats a
	 * success they have no reason to doubt; every other kind is locatable by id alone
	 * and is unaffected.
	 */
	@Operation(summary = "Freeze content: preserved, and unreachable to everyone including admins")
	@PostMapping("/freeze")
	public FreezeResponse freeze(@RequestBody @Valid FreezeRequest request) {
		User admin = currentUser.require();
		if (request.targetType() == FrozenTargetType.ATTACHMENT
				&& (request.contextId() == null || request.contextId().isBlank())) {
			throw ApiException.badRequest("error.moderation.freezeContextRequired");
		}
		return FreezeResponse.of(frozen.freeze(new FrozenContentService.Request(
				request.targetType(), request.targetId(), request.contextId(), List.of(),
				ModerationCategory.SEXUAL_MINORS, null, null, admin,
				ContentReportService.trimNote(request.note()))));
	}

	/**
	 * Releases a freeze.
	 *
	 * <p>Separate from {@code POST /reports/{id}/handle}, and that separation is the
	 * point: dismissing a report and releasing content are different claims, and the
	 * moderator can only honestly make the first — they have not seen the content
	 * and must not. Collapsing the two would make "dismiss" the one-click way to
	 * look.
	 *
	 * <p>A note is mandatory (400 without one). An unfreeze is an administrative
	 * correction — wrong target, malicious reporter, an authority that came back —
	 * never a judgement on the merits of something nobody was allowed to read.
	 */
	@Operation(summary = "Release a freeze (mandatory note; audited)")
	@DeleteMapping("/freeze")
	public FreezeResponse unfreeze(@RequestBody @Valid FreezeRequest request) {
		User admin = currentUser.require();
		return FreezeResponse.of(
				frozen.unfreeze(admin, request.targetType(), request.targetId(), request.note()));
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
