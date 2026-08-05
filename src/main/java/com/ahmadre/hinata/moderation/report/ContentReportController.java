package com.ahmadre.hinata.moderation.report;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The two things Apple Guideline 1.2 requires a product with user-generated content
 * to give every user: a way to report what they are looking at, and a way to stop
 * seeing a specific person.
 *
 * <p>Both are open to any authenticated user against content they can see — there is
 * no role gate, because a safety mechanism only members of the right project can
 * reach is not a safety mechanism.
 *
 * <p>There is no class-level {@code @RequestMapping} on purpose: reporting is a write
 * about someone else's content and belongs under {@code /reports}, while a block is
 * part of a user's own account and belongs under {@code /me}. Putting either one on
 * the other's prefix would make the resource lie about who owns it. The reporting
 * path is {@code /api/v1/reports/content} rather than {@code /api/v1/reports}, which
 * the analytics {@code ReportController} already owns — same English word, entirely
 * different resource.
 */
@Tag(name = "Moderation")
@RestController
@RequiredArgsConstructor
public class ContentReportController {

	private final ContentReportService reports;
	private final UserBlockService userBlocks;
	private final CurrentUser currentUser;

	// --- DTOs -----------------------------------------------------------------

	/**
	 * A notice about one piece of content.
	 *
	 * @param contextId owning issue of an attachment — required only for
	 *                  {@code ATTACHMENT}, which has no addressable existence outside
	 *                  the issue it is embedded in
	 * @param note      the reporter's own words; bounded because it is stored, read by
	 *                  an admin, and moderated like any other user text
	 */
	public record ContentReportRequest(
			@NotNull ContentReport.TargetType targetType,
			@NotBlank @Size(max = 120) String targetId,
			@Size(max = 120) String contextId,
			@NotNull ContentReport.ReportReason reason,
			@Size(max = ContentReportService.MAX_NOTE_LENGTH) String note) {
	}

	/**
	 * Confirmation of receipt. Deliberately says nothing about what will happen to the
	 * content: the report has been recorded and a human will judge it, and any stronger
	 * promise at submission time would be one the product cannot keep.
	 */
	public record ContentReportResponse(String id, ContentReport.TargetType targetType,
			String targetId, ContentReport.ReportReason reason, ContentReport.State state,
			Instant createdAt) {

		static ContentReportResponse from(ContentReport report) {
			return new ContentReportResponse(report.getId(), report.getTargetType(),
					report.getTargetId(), report.getReason(), report.getState(), report.getCreatedAt());
		}
	}

	/**
	 * One entry of the blocked list. Carries enough to render a row and nothing more;
	 * {@code displayName} is null when the account has since been deleted.
	 */
	public record BlockedUserResponse(String userId, String displayName, String username,
			String avatarUrl, Instant blockedAt) {

		static BlockedUserResponse from(UserBlockService.BlockedUser blocked) {
			User user = blocked.user();
			return new BlockedUserResponse(blocked.block().getBlockedId(),
					user == null ? null : user.getDisplayName(),
					user == null ? null : user.getUsername(),
					user == null ? null : user.getAvatarUrl(),
					blocked.block().getCreatedAt());
		}
	}

	// --- Reporting ------------------------------------------------------------

	@Operation(summary = "Report content or a user for review")
	@PostMapping("/api/v1/reports/content")
	@ResponseStatus(HttpStatus.CREATED)
	public ContentReportResponse report(@RequestBody @Valid ContentReportRequest request) {
		return ContentReportResponse.from(reports.file(currentUser.require(), request.targetType(),
				request.targetId(), request.contextId(), request.reason(), request.note()));
	}

	// --- Blocking -------------------------------------------------------------

	@Operation(summary = "List the users I have blocked")
	@GetMapping("/api/v1/me/blocks")
	public List<BlockedUserResponse> blocks() {
		return userBlocks.listFor(currentUser.requireId()).stream()
				.map(BlockedUserResponse::from)
				.toList();
	}

	/**
	 * 200 rather than 201: blocking is idempotent, so a repeat call is not the
	 * creation of anything and a client retrying after a dropped response must not be
	 * told something different the second time.
	 */
	@Operation(summary = "Block a user")
	@PostMapping("/api/v1/me/blocks/{userId}")
	public BlockedUserResponse block(@PathVariable String userId) {
		return BlockedUserResponse.from(userBlocks.block(currentUser.require(), userId));
	}

	@Operation(summary = "Unblock a user")
	@DeleteMapping("/api/v1/me/blocks/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unblock(@PathVariable String userId) {
		userBlocks.unblock(currentUser.require(), userId);
	}
}
