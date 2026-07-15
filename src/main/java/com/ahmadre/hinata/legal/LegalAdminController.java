package com.ahmadre.hinata.legal;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin management of the legal documents: read the currently effective
 * markdown (for the editor) and replace it. ADMIN-only via the
 * {@code /api/v1/admin/**} security matcher — no per-method annotation needed.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/legal")
@RequiredArgsConstructor
public class LegalAdminController {

	private final LegalService legal;
	private final AuditService audit;
	private final CurrentUser currentUser;

	public record UpdateLegalRequest(@NotBlank String markdown) {
	}

	@Operation(summary = "Read the currently effective markdown of a legal document")
	@GetMapping("/{type}/{lang}")
	public LegalController.LegalResponse get(@PathVariable String type, @PathVariable String lang) {
		LegalService.LegalContent content = legal.get(type, lang);
		return new LegalController.LegalResponse(content.type(), content.lang(),
				content.markdown(), content.updatedAt());
	}

	@Operation(summary = "Replace a legal document's markdown (stored in object storage)")
	@PutMapping("/{type}/{lang}")
	public LegalController.LegalResponse update(
			@PathVariable String type,
			@PathVariable String lang,
			@Valid @RequestBody UpdateLegalRequest request) {
		var user = currentUser.require();
		LegalDocument doc = legal.update(type, lang, request.markdown(), user.getId());
		audit.event(AuditAction.LEGAL_DOCUMENT_UPDATED)
				.actor(user)
				.meta("document", doc.getId())
				.log();
		return new LegalController.LegalResponse(doc.getType(), doc.getLang(),
				request.markdown(), doc.getUpdatedAt());
	}
}
