package hn.asta.hivora.meta;

import hn.asta.hivora.config.HivoraProperties;
import hn.asta.hivora.setup.ServerSettings;
import hn.asta.hivora.setup.SettingsService;
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

@Tag(name = "Public", description = "Unauthenticated server metadata")
@RestController
@RequiredArgsConstructor
@Slf4j
public class MetaController {

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private final HivoraProperties properties;
	private final SettingsService settings;

	@Value("${hivora.version:1.0.0}")
	private String serverVersion;

	public record Meta(String serverVersion, String minAppVersion, String organizationName,
			String logoUrl, boolean setupCompleted, String privacyPolicyUrl,
			Map<String, Boolean> featureFlags, UploadLimits uploadLimits) {
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
		HivoraProperties.App appDefaults = properties.getApp();
		HivoraProperties.Storage storage = properties.getStorage();
		Map<String, Boolean> featureFlags = app.getFeatureFlags() != null && !app.getFeatureFlags().isEmpty()
				? app.getFeatureFlags()
				: appDefaults.getFeatureFlags();
		return new Meta(
				serverVersion,
				firstNonBlank(app.getMinVersion(), appDefaults.getMinVersion()),
				current.getOrganizationName(),
				current.getGeneral().getLogoUrl(),
				current.isSetupCompleted(),
				firstNonBlank(app.getPrivacyPolicyUrl(), appDefaults.getPrivacyPolicyUrl()),
				featureFlags,
				new UploadLimits(storage.getMaxUploadMb(), storage.getMaxFilesPerRequest(),
						storage.getMaxRequestMb(), storage.getAllowedContentTypes()));
	}

	@Operation(summary = "Organization logo", description = "Proxies the configured logo URL so clients (incl. the web app and the PDF export) can load it same-origin without CORS restrictions.")
	@SecurityRequirements
	@GetMapping("/api/v1/meta/logo")
	public ResponseEntity<byte[]> logo() {
		String url = settings.get().getGeneral().getLogoUrl();
		if (url == null || url.isBlank()) {
			return ResponseEntity.notFound().build();
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
					.timeout(Duration.ofSeconds(10))
					.header("User-Agent", "Hivora-Server")
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
