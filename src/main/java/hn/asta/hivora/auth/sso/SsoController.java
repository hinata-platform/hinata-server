package hn.asta.hivora.auth.sso;

import hn.asta.hivora.setup.ServerSettings;
import hn.asta.hivora.setup.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "SSO")
@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
public class SsoController {

	private final SettingsService settings;

	public record SsoProvider(String id, String displayName, String loginUrl) {
	}

	@Operation(summary = "List enabled SSO providers", description = "Used by the app to render SSO login buttons. No authentication required.")
	@SecurityRequirements
	@GetMapping("/providers")
	public List<SsoProvider> providers() {
		ServerSettings current = settings.get();
		List<SsoProvider> providers = new ArrayList<>();
		if (current.getOidc().isEnabled()) {
			providers.add(new SsoProvider("oidc", current.getOidc().getDisplayName(),
					"/oauth2/authorization/" + DynamicClientRegistrationRepository.OIDC_ID));
		}
		if (current.getOauth2().isEnabled()) {
			providers.add(new SsoProvider("oauth2", current.getOauth2().getDisplayName(),
					"/oauth2/authorization/" + DynamicClientRegistrationRepository.OAUTH2_ID));
		}
		if (current.getSaml().isEnabled()) {
			providers.add(new SsoProvider("saml", current.getSaml().getDisplayName(),
					"/saml2/authenticate/" + DynamicRelyingPartyRegistrationRepository.SAML_ID));
		}
		return providers;
	}
}
