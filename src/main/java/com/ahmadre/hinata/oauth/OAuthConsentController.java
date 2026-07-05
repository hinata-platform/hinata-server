package com.ahmadre.hinata.oauth;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authenticated consent surface backing the web consent page. The user must be
 * signed in (app JWT), which is how the authorization flow reuses hinata's full
 * login — password, 2FA and SSO — without the OAuth server needing its own login
 * page. The user's approval here is what mints the authorization code.
 */
@RestController
@RequestMapping("/api/v1/oauth")
@RequiredArgsConstructor
public class OAuthConsentController {

	private final OAuthService oauth;
	private final CurrentUser currentUser;
	private final AuditService audit;

	public record ConsentDecision(String requestId, boolean approved, List<String> grantedScopes) {
	}

	public record ConsentResult(String redirectUri) {
	}

	@GetMapping("/consent/{requestId}")
	public OAuthService.ConsentInfo consentInfo(@PathVariable String requestId) {
		currentUser.require();
		return oauth.consentInfo(requestId);
	}

	@PostMapping("/consent")
	public ConsentResult decide(@RequestBody ConsentDecision decision) {
		User me = currentUser.require();
		String redirectUri = oauth.decide(decision.requestId(), me.getId(),
				decision.approved(), decision.grantedScopes());
		if (decision.approved()) {
			List<String> scopes = decision.grantedScopes() == null ? List.of() : decision.grantedScopes();
			audit.event(AuditAction.MCP_OAUTH_AUTHORIZED).actor(me)
					.meta("scopes", String.join(",", scopes)).log();
		}
		return new ConsentResult(redirectUri);
	}
}
