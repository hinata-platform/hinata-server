package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
}
