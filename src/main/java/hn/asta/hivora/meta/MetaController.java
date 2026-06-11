package hn.asta.hivora.meta;

import hn.asta.hivora.config.HivoraProperties;
import hn.asta.hivora.setup.ServerSettings;
import hn.asta.hivora.setup.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Public", description = "Unauthenticated server metadata")
@RestController
@RequiredArgsConstructor
public class MetaController {

	private final HivoraProperties properties;
	private final SettingsService settings;

	@Value("${hivora.version:1.0.0}")
	private String serverVersion;

	public record Meta(String serverVersion, String minAppVersion, String organizationName,
			boolean setupCompleted, String privacyPolicyUrl, Map<String, Boolean> featureFlags) {
	}

	@Operation(summary = "Server metadata", description = "Returns server version, minimum required app version, feature flags and branding. Called by the app on every start.")
	@SecurityRequirements
	@GetMapping("/api/v1/meta")
	public Meta meta() {
		ServerSettings current = settings.get();
		return new Meta(
				serverVersion,
				properties.getApp().getMinVersion(),
				current.getOrganizationName(),
				current.isSetupCompleted(),
				properties.getApp().getPrivacyPolicyUrl(),
				properties.getApp().getFeatureFlags());
	}
}
