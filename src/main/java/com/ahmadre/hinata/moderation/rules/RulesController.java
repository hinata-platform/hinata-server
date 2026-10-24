package com.ahmadre.hinata.moderation.rules;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.Languages;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * The house rules as a member reads and accepts them (HIN-175).
 */
@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class RulesController {

	private final HouseRulesService rules;
	private final CurrentUser currentUser;

	record Acceptance(@NotNull Integer version) {
	}

	@GetMapping("/me/rules")
	HouseRulesService.RulesView current(@RequestHeader(value = "Accept-Language", required = false) String language) {
		User user = currentUser.require();
		return rules.view(user, languageOf(language, user));
	}

	@PostMapping("/me/rules/acceptance")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void accept(@RequestBody @jakarta.validation.Valid Acceptance acceptance) {
		rules.accept(currentUser.require(), acceptance.version());
	}

	/** The language asked for when the server has the rules in it, else the person's own. */
	private static String languageOf(String acceptLanguage, User user) {
		if (acceptLanguage != null && !acceptLanguage.isBlank()) {
			String tag = acceptLanguage.split(",")[0].strip().split("[-_;]")[0].toLowerCase(Locale.ROOT);
			if (Languages.SUPPORTED.contains(tag)) {
				return tag;
			}
		}
		String own = user.getLocale() == null ? "" : user.getLocale().split("[-_]")[0].toLowerCase(Locale.ROOT);
		return Languages.SUPPORTED.contains(own) ? own : "en";
	}
}
