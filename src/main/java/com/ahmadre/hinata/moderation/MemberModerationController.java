package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** What every member does with moderation (HIN-170): report. */
@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1/me/moderation")
@RequiredArgsConstructor
public class MemberModerationController {

	private final ContentReportService reports;
	private final AppealService appeals;
	private final CurrentUser currentUser;

	/**
	 * Accepted, and nothing more: no case, no state, no number of other reports. The bell
	 * confirms that it arrived.
	 */
	@Operation(summary = "Report content or a person")
	@PostMapping("/reports")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Map<String, Boolean> report(@Valid @RequestBody ContentReportService.ReportRequest request) {
		reports.submit(request, currentUser.require());
		return Map.of("received", true);
	}

	/** Only the person responsible reaches it; there is no list, the bell and the mail lead here. */
	@Operation(summary = "A notice of moderation about my content, with its appeal")
	@GetMapping("/notices/{caseId}")
	public Statement notice(@PathVariable String caseId) {
		return appeals.notice(caseId, currentUser.require());
	}

	@Operation(summary = "Appeal a decision of moderation")
	@PostMapping("/notices/{caseId}/appeal")
	public Statement appeal(@PathVariable String caseId, @Valid @RequestBody AppealService.AppealRequest request) {
		return appeals.appeal(caseId, currentUser.require(), request);
	}
}
