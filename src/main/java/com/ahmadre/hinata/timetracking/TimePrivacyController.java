package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Who sees my time data?" — the notice and the computed visibility, for the person
 * they describe. See {@link TimePrivacyService}. Behind the module gate like the rest
 * of {@code /api/v1/time}: with the module off there is nothing to be told about.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/privacy")
@RequiredArgsConstructor
public class TimePrivacyController {

	private final TimePrivacyService privacy;
	private final CurrentUser currentUser;

	@GetMapping
	public TimePrivacyService.Privacy privacy() {
		return privacy.privacy(currentUser.require());
	}

	/** "Understood": records the first moment the person saw the notice. Idempotent. */
	@PostMapping("/acknowledge")
	public TimePrivacyService.Privacy acknowledge() {
		return privacy.acknowledge(currentUser.require());
	}
}
