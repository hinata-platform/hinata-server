package hn.asta.hivora.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

/**
 * Central, environment-driven configuration. Every value can be supplied via
 * HIVORA_* environment variables (relaxed binding), see .env.example.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "hivora")
public class HivoraProperties {

	/** Public base URL of this server, e.g. https://hivora.example.org */
	@NotBlank
	private String baseUrl = "http://localhost:8080";

	/** Optional defaults used to pre-fill or skip the first-run setup wizard. */
	private Setup setup = new Setup();

	private Jwt jwt = new Jwt();
	private RateLimit rateLimit = new RateLimit();
	private Cors cors = new Cors();
	private App app = new App();
	private Storage storage = new Storage();

	@Getter
	@Setter
	public static class Setup {
		/** Organization shown in the app, e.g. "AStA Hochschule Niederrhein". */
		private String organizationName;
		private String adminEmail;
		private String adminUsername;
		private String adminPassword;
		private String adminDisplayName;
		/** If true and all admin values are present, setup completes automatically on boot. */
		private boolean autoComplete = false;
	}

	@Getter
	@Setter
	public static class Jwt {
		/** HS512 secret, minimum 64 characters. MUST be overridden in production. */
		@Size(min = 64, message = "hivora.jwt.secret must be at least 64 characters")
		private String secret = "change-me-change-me-change-me-change-me-change-me-change-me-1234";
		@Min(60)
		private long accessTokenSeconds = 900;
		@Min(300)
		private long refreshTokenSeconds = 1209600; // 14 days
	}

	@Getter
	@Setter
	public static class RateLimit {
		private boolean enabled = true;
		/** Requests per minute per client IP for the general API. */
		@Min(10)
		private int apiPerMinute = 300;
		/** Requests per minute per client IP for authentication endpoints. */
		@Min(3)
		private int authPerMinute = 10;
		/** Failed logins per account before a temporary database-backed block. */
		@Min(3)
		private int maxLoginFailures = 5;
		/** Minutes an account/IP pair stays blocked after too many failures. */
		@Min(1)
		private int loginBlockMinutes = 15;
	}

	@Getter
	@Setter
	public static class Cors {
		private List<String> allowedOrigins = List.of();
	}

	/** Values served to the Flutter app via /api/v1/meta. */
	@Getter
	@Setter
	public static class App {
		/** Minimum app version; older clients are forced to update. */
		private String minVersion = "1.0.0";
		private String privacyPolicyUrl = "";
		/** Deep link scheme the app registers for SSO callbacks. */
		private String callbackScheme = "hivora";
		private Map<String, Boolean> featureFlags = Map.of();
	}

	@Getter
	@Setter
	public static class Storage {
		private String endpoint = "http://localhost:9000";
		private String accessKey = "";
		private String secretKey = "";
		private String bucket = "hivora";
		private String region = "us-east-1";
		/** Max upload size in megabytes. */
		@Min(1)
		private int maxUploadMb = 25;
		private List<String> allowedContentTypes = List.of(
				"image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
				"application/pdf", "text/plain", "text/csv", "application/zip",
				"application/json", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
	}
}
