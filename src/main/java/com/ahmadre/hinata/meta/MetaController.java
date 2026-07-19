package com.ahmadre.hinata.meta;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.OrganizationLogoService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Tag(name = "Public", description = "Unauthenticated server metadata")
@RestController
@RequiredArgsConstructor
@Slf4j
public class MetaController {

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private final HinataProperties properties;
	private final SettingsService settings;
	private final com.ahmadre.hinata.auth.AuthPolicy authPolicy;
	private final com.ahmadre.hinata.auth.SecurityPolicy securityPolicy;
	private final com.ahmadre.hinata.mcp.McpSettings mcpSettings;
	private final OrganizationLogoService logoService;

	@Value("${hinata.version:1.0.0}")
	private String serverVersion;

	public record Meta(String serverVersion, String minAppVersion, String organizationName,
			String logoUrl, boolean setupCompleted, String privacyPolicyUrl,
			String iosStoreUrl, String androidStoreUrl, String macosStoreUrl,
			Map<String, Boolean> featureFlags, boolean localAuthEnabled,
			boolean registrationEnabled, boolean adminApprovalRequired, UploadLimits uploadLimits,
			int passwordMinLength) {
	}

	/** Attachment upload constraints so the client can validate before sending. */
	public record UploadLimits(int maxFileMb, int maxFiles, int maxRequestMb,
			java.util.List<String> allowedContentTypes) {
	}

	@Operation(summary = "Server metadata", description = "Returns server version, minimum required app version, feature flags and branding. Called by the app on every start.")
	@SecurityRequirements
	@GetMapping("/api/v1/meta")
	public Meta meta() {
		ServerSettings current = settings.get();
		ServerSettings.App app = current.getApp();
		HinataProperties.App appDefaults = properties.getApp();
		HinataProperties.Storage storage = properties.getStorage();
		// Start from the env defaults, then let any admin DB override win per-key —
		// NOT "DB wins entirely once non-empty", which would permanently hide any
		// new default flag (e.g. a freshly shipped feature) the moment an admin
		// has toggled anything else. Then surface the effective MCP toggle (admin
		// DB override, else env default) as a flag the app uses to show/hide the
		// Personal Access Token UI.
		Map<String, Boolean> featureFlags = new java.util.LinkedHashMap<>(appDefaults.getFeatureFlags());
		if (app.getFeatureFlags() != null) {
			featureFlags.putAll(app.getFeatureFlags());
		}
		featureFlags.put("mcp", mcpSettings.enabled());
		return new Meta(
				serverVersion,
				firstNonBlank(app.getMinVersion(), appDefaults.getMinVersion()),
				current.getOrganizationName(),
				current.getGeneral().getLogoUrl(),
				current.isSetupCompleted(),
				firstNonBlank(app.getPrivacyPolicyUrl(), appDefaults.getPrivacyPolicyUrl()),
				firstNonBlank(app.getIosStoreUrl(), appDefaults.getIosStoreUrl()),
				firstNonBlank(app.getAndroidStoreUrl(), appDefaults.getAndroidStoreUrl()),
				firstNonBlank(app.getMacosStoreUrl(), appDefaults.getMacosStoreUrl()),
				featureFlags,
				authPolicy.localAuthEnabled(),
				authPolicy.registrationEnabled(),
				authPolicy.requireAdminApproval(),
				new UploadLimits(storage.getMaxUploadMb(), storage.getMaxFilesPerRequest(),
						storage.getMaxRequestMb(), storage.getAllowedContentTypes()),
				securityPolicy.passwordMinLength());
	}

	@Operation(summary = "Organization logo", description = "Serves the organization logo same-origin so clients (incl. the web app and the PDF export) load it without CORS restrictions. An uploaded logo is streamed from object storage; a configured external URL is proxied.")
	@SecurityRequirements
	@GetMapping("/api/v1/meta/logo")
	public ResponseEntity<byte[]> logo() {
		String url = settings.get().getGeneral().getLogoUrl();
		if (url == null || url.isBlank()) {
			return ResponseEntity.notFound().build();
		}
		// An uploaded logo lives in object storage and logoUrl holds our internal
		// proxy path (not an absolute URL) — stream those bytes straight back.
		if (OrganizationLogoService.isInternal(url)) {
			Optional<StorageService.StoredObject> stored = logoService.load();
			if (stored.isEmpty()) {
				return ResponseEntity.notFound().build();
			}
			StorageService.StoredObject obj = stored.get();
			MediaType contentType = obj.contentType() != null
					? parseMediaType(obj.contentType())
					: MediaType.APPLICATION_OCTET_STREAM;
			return ResponseEntity.ok()
					.contentType(contentType)
					.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
					.body(obj.data());
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
					.timeout(Duration.ofSeconds(10))
					.header("User-Agent", "Hinata-Server")
					.GET()
					.build();
			HttpResponse<byte[]> response =
					HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
			byte[] body = response.body();
			if (response.statusCode() >= 300 || body == null || body.length == 0) {
				return ResponseEntity.notFound().build();
			}
			MediaType contentType = response.headers().firstValue("content-type")
					.map(MetaController::parseMediaType)
					.orElse(MediaType.APPLICATION_OCTET_STREAM);
			return ResponseEntity.ok()
					.contentType(contentType)
					.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
					.headers(h -> { /* same-origin proxy: no ACAO, don't widen CORS (A05) */ })
					.body(body);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while proxying organization logo from {}", url);
			return ResponseEntity.notFound().build();
		} catch (Exception e) {
			log.warn("Failed to proxy organization logo from {}: {}", url, e.toString());
			return ResponseEntity.notFound().build();
		}
	}

	private static String firstNonBlank(String preferred, String fallback) {
		return preferred != null && !preferred.isBlank() ? preferred : fallback;
	}

	private static MediaType parseMediaType(String raw) {
		try {
			return MediaType.parseMediaType(raw);
		} catch (Exception ignored) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}
}
