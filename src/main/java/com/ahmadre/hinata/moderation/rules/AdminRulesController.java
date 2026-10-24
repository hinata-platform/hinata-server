package com.ahmadre.hinata.moderation.rules;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.setup.ServerSettings.RulesMode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The versions of the house rules as the admin area shows them (HIN-175), and the one action that
 * is not a settings save: asking everybody to accept the rules again. Administrators only, by
 * the path.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/moderation/rules")
@RequiredArgsConstructor
class AdminRulesController {

	private final HouseRulesService rules;
	private final CurrentUser currentUser;

	/** The current version, since when it holds, and the last one everybody had to accept. */
	record RulesState(Integer version, RulesMode mode, Instant since, Integer requiredVersion, String publicUrl) {
	}

	@GetMapping
	RulesState state() {
		return stateOf(rules.latest());
	}

	@PostMapping("/reacceptance")
	RulesState requireReacceptance() {
		return stateOf(rules.requireReacceptance(currentUser.require()));
	}

	private RulesState stateOf(RulesVersion version) {
		return version == null ? new RulesState(null, RulesMode.BUILT_IN, null, null, null)
				: new RulesState(version.getVersion(), version.getMode(), version.getCreatedAt(),
						rules.requiredVersion(), rules.publicRulesUrl(version.getVersion()));
	}
}
