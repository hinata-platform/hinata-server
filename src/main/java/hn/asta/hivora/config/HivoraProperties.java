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
	private Security security = new Security();
	private Mongodb mongodb = new Mongodb();

	@Getter
	@Setter
	public static class Security {
		/**
		 * CIDR ranges of reverse proxies that are allowed to set
		 * {@code X-Forwarded-For}. When empty (the secure default) the header is
		 * ignored entirely and the direct socket address is used, so a client
		 * cannot spoof its IP to bypass rate limiting or the login lockout
		 * (OWASP A07). Behind a proxy/tunnel, set this to the proxy's address,
		 * e.g. {@code 127.0.0.1/32} for a local ngrok agent.
		 */
		private List<String> trustedProxies = List.of();
	}

	@Getter
	@Setter
	public static class Mongodb {
		private Tls tls = new Tls();

		/**
		 * Mutual-TLS / X.509 settings for the MongoDB connection. When enabled,
		 * the driver presents the configured client certificate (keystore) and
		 * verifies the server against the CA (truststore); combine with
		 * {@code authMechanism=MONGODB-X509&authSource=$external} in the URI.
		 */
		@Getter
		@Setter
		public static class Tls {
			private boolean enabled = false;
			/** PKCS#12 keystore holding the client certificate + private key. */
			private String keyStore = "";
			private String keyStorePassword = "";
			/** PKCS#12/JKS truststore holding the CA that signed the server cert. */
			private String trustStore = "";
			private String trustStorePassword = "";
		}
	}

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
				// image/svg+xml intentionally excluded: SVG can carry inline
				// JavaScript (stored-XSS risk if ever rendered inline).
				"image/png", "image/jpeg", "image/gif", "image/webp",
				"application/pdf", "text/plain", "text/csv", "application/zip",
				"application/json", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
	}
}
