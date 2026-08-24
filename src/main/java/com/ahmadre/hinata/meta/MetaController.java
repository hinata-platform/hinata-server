package com.ahmadre.hinata.meta;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
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

import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Tag(name = "Public", description = "Unauthenticated server metadata")
@RestController
@RequiredArgsConstructor
@Slf4j
public class MetaController {

	private final HinataProperties properties;
	private final SettingsService settings;
	private final com.ahmadre.hinata.auth.AuthPolicy authPolicy;
	private final com.ahmadre.hinata.auth.SecurityPolicy securityPolicy;
	private final com.ahmadre.hinata.mcp.McpSettings mcpSettings;
	private final BrandLogoService brandLogo;

	@Value("${hinata.version:1.0.0}")
	private String serverVersion;

	public record Meta(String serverVersion, String minAppVersion, String organizationName,
			String logoUrl, boolean setupCompleted, String privacyPolicyUrl,
			String iosStoreUrl, String androidStoreUrl, String macosStoreUrl,
			String windowsStoreUrl, String linuxStoreUrl,
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
				firstNonBlank(app.getWindowsStoreUrl(), appDefaults.getWindowsStoreUrl()),
				firstNonBlank(app.getLinuxStoreUrl(), appDefaults.getLinuxStoreUrl()),
				featureFlags,
				authPolicy.localAuthEnabled(),
				authPolicy.registrationEnabled(),
				authPolicy.requireAdminApproval(),
				new UploadLimits(storage.getMaxUploadMb(), storage.getMaxFilesPerRequest(),
						storage.getMaxRequestMb(), storage.getAllowedContentTypes()),
				securityPolicy.passwordMinLength());
	}

	@Operation(summary = "Organization logo", description = "Serves the organization logo same-origin so clients (incl. the web app and the PDF export) load it without CORS restrictions. An uploaded logo is streamed from object storage; a configured external URL is fetched server-side through the SSRF-hardened image fetcher and cached.")
	@SecurityRequirements
	@GetMapping("/api/v1/meta/logo")
	public ResponseEntity<byte[]> logo(
			@org.springframework.web.bind.annotation.RequestHeader(
					value = "If-None-Match", required = false) String ifNoneMatch) {
		BrandLogoService.BrandAsset asset = brandLogo.display().orElse(null);
		if (asset == null) {
			return ResponseEntity.notFound().build();
		}
		String etag = etagOf(asset.bytes());
		if (etag.equals(ifNoneMatch)) {
			return ResponseEntity.status(304).eTag(etag).build();
		}
		return ResponseEntity.ok()
				.contentType(parseMediaType(asset.contentType()))
				.eTag(etag)
				// Revalidate rather than expire. The ?v= token lives in the *stored*
				// logoUrl, not in the URL a client requests — clients call the bare
				// /api/v1/meta/logo — so a freshness window here would let a shared
				// cache keep serving a logo the operator has already replaced or
				// taken down, with no way to purge it. The strong ETag above makes
				// revalidation a 304, so the bytes are still only sent once.
				.cacheControl(asset.uploaded()
						? CacheControl.noCache().cachePublic()
						: CacheControl.noCache().cachePrivate())
				// This endpoint returns third-party bytes under our own origin. It is
				// never a document: naming it as an attachment-style disposition and
				// stripping every capability keeps a hostile host from using our
				// domain to host a page (nosniff and the chain-wide CSP already stop
				// script; `form-action` does not inherit from default-src, so an HTML
				// body would otherwise still render a same-origin credential form).
				.header("Content-Disposition", "inline; filename=\"logo\"")
				.header("Content-Security-Policy",
						"default-src 'none'; style-src 'unsafe-inline'; sandbox")
				.header("X-Content-Type-Options", "nosniff")
				.body(asset.bytes());
	}

	/** Strong validator over the bytes, so a client re-check costs a 304. */
	private static String etagOf(byte[] bytes) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
			return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			return "\"" + Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "\"";
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
