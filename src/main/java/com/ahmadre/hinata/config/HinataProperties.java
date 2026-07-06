package com.ahmadre.hinata.config;

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
 * HINATA_* environment variables (relaxed binding), see .env.example.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "hinata")
public class HinataProperties {

	/** Public base URL of this server, e.g. https://hinata.example.org */
	@NotBlank
	private String baseUrl = "http://localhost:8080";

	/**
	 * Public base URL of the Flutter frontend (web app), e.g.
	 * {@code http://localhost:3000}. Email deep links point here so the user lands
	 * on a real in-app page rather than a backend-rendered form. Blank ⇒ falls
	 * back to {@link #baseUrl}.
	 */
	private String webBaseUrl = "";

	/** Optional defaults used to pre-fill or skip the first-run setup wizard. */
	private Setup setup = new Setup();

	/** Frontend base URL for deep links ({@link #webBaseUrl} or {@link #baseUrl}). */
	public String webBase() {
		String url = (webBaseUrl != null && !webBaseUrl.isBlank()) ? webBaseUrl : baseUrl;
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** Email deep link to the in-app invitation-acceptance page. */
	public String inviteLink(String token) {
		return webBase() + "/invite?token=" + enc(token) + "&server=" + enc(baseUrl);
	}

	/** Email deep link to the in-app password-reset page. */
	public String resetLink(String token) {
		return webBase() + "/reset-password?token=" + enc(token) + "&server=" + enc(baseUrl);
	}

	private static String enc(String value) {
		return java.net.URLEncoder.encode(value == null ? "" : value,
				java.nio.charset.StandardCharsets.UTF_8);
	}

	private Jwt jwt = new Jwt();
	private RateLimit rateLimit = new RateLimit();
	private Cors cors = new Cors();
	private App app = new App();
	private Storage storage = new Storage();
	private Security security = new Security();
	private Mongodb mongodb = new Mongodb();
	private Demo demo = new Demo();
	private Gateway gateway = new Gateway();
	private GitIntegration gitIntegration = new GitIntegration();
	private Mcp mcp = new Mcp();

	/**
	 * Hinata Connect — the single central service every Hinata server uses for the
	 * white-label app: push fan-out (it owns the published app's FCM credentials)
	 * and the verified universal-link relay (invite/reset deep links). The URL is a
	 * constant shared by all self-hosted servers; only override for testing. Push
	 * is no longer configured per-server (no Firebase needed on a self-hoster).
	 */
	@Getter
	@Setter
	public static class Gateway {
		private boolean enabled = true;
		/** Base URL of the central gateway, e.g. https://connect.hinata.ahmadre.com */
		private String baseUrl = "https://connect.hinata.ahmadre.com";
		/** Sent as X-Bootstrap-Secret when the gateway gates registration (blank = open). */
		private String bootstrapSecret = "";
	}

	/**
	 * Git integration — per-project repository connections + development
	 * automation. OAuth is brokered <em>server-side</em>; the client never
	 * receives a provider token. Provider client id/secret pairs enable the real
	 * OAuth app flow; where they are blank the connect wizard's owner/repo picking
	 * is served from an emulated account graph, so the whole flow is still
	 * exercisable end-to-end and the app carries no fixture data.
	 */
	@Getter
	@Setter
	public static class GitIntegration {
		private String githubClientId = "";
		private String githubClientSecret = "";
		private String gitlabClientId = "";
		private String gitlabClientSecret = "";
		private String bitbucketClientId = "";
		private String bitbucketClientSecret = "";
		/** Public base URL provider webhooks POST to, e.g. {@code https://…/api/v1/webhooks/git}. */
		private String webhookBaseUrl = "";
		/** Key used to encrypt stored provider access tokens at rest (>= 16 chars). */
		private String tokenSecret = "change-me-change-me-change-me-git-tokens";
	}

	/**
	 * Demo / test data seeding. Never runs under the {@code prod} profile (see
	 * {@code DemoSeeder}, which is {@code @Profile("!prod")}); intended for local
	 * dev and integration tests that need a populated, deterministic workspace.
	 */
	@Getter
	@Setter
	public static class Demo {
		/** Seed the demo workspace on boot. Idempotent: skipped once any project exists. */
		private boolean seed = false;
		/**
		 * Wipe the existing workspace (users, projects, boards, sprints, issues,
		 * teams, tracked work) and re-seed on every boot, so tests get the same
		 * deterministic dataset. Requires {@link #seed} = true. Ignored in prod.
		 */
		private boolean reset = false;
	}

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
		@Size(min = 64, message = "hinata.jwt.secret must be at least 64 characters")
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
		/**
		 * Requests per minute per client IP for the MCP endpoint. Kept on its own
		 * budget so AI-client traffic can neither exhaust nor be exhausted by the
		 * general API bucket.
		 */
		@Min(10)
		private int mcpPerMinute = 120;
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
		/** App Store listing the iOS app links to when an update is required. */
		private String iosStoreUrl = "";
		/** Play Store listing the Android app links to when an update is required. */
		private String androidStoreUrl = "";
		/** Mac App Store listing the macOS app links to when an update is required. */
		private String macosStoreUrl = "";
		/** Deep link scheme the app registers for SSO callbacks. */
		private String callbackScheme = "hinata";
		private Map<String, Boolean> featureFlags = Map.of();

		/**
		 * Master switch for local (email/password) authentication. When false, local
		 * sign-in, self-registration and forgot-password are all disabled and users
		 * must authenticate via SSO. Overridable at runtime in the admin settings.
		 */
		private boolean localAuthEnabled = true;

		/** Whether the public self-registration (sign-up) flow is open. */
		private boolean registrationEnabled = true;

		/**
		 * When true, a self-registered user who has verified their email still needs
		 * an admin to approve them before they can sign in. Default off.
		 */
		private boolean requireAdminApproval = false;
	}

	/**
	 * MCP (Model Context Protocol) server. Exposes a curated set of read/write
	 * tools to AI clients (Claude, Cursor, …), authenticated with scoped Personal
	 * Access Tokens on the dedicated {@code /mcp} endpoint. These are the ENV
	 * defaults; an admin can override {@code enabled}/{@code maxPatsPerUser} at
	 * runtime via {@code ServerSettings} (DB-overrides-ENV, see {@code McpSettings}).
	 */
	@Getter
	@Setter
	public static class Mcp {
		/** Feature master switch. Gates the /mcp transport, PAT UI and tools. */
		private boolean enabled = true;
		/** Advertised MCP server name (also {@code spring.ai.mcp.server.name}). */
		private String serverName = "hinata";
		/** Max active Personal Access Tokens a single user may hold. */
		@Min(1)
		private int maxPatsPerUser = 20;
		/** Default PAT lifetime in days when none is given. {@code 0} ⇒ never expires. */
		@Min(0)
		private long defaultTokenTtlDays = 90;

		/**
		 * Phase-2 OAuth 2.1 authorization server for the MCP resource. When enabled,
		 * this server advertises RFC 9728 / RFC 8414 metadata and runs the
		 * authorization-code (PKCE) + refresh flows so AI clients can connect with a
		 * one-click "Connect" button (no PAT paste). PATs keep working regardless.
		 */
		private boolean oauthEnabled = true;
		/** Allow anonymous RFC 7591 Dynamic Client Registration (Claude needs it). */
		private boolean dynamicClientRegistration = true;
		/** MCP OAuth access-token lifetime (seconds). */
		@Min(60)
		private long accessTokenTtlSeconds = 3600;
		/** Authorization-code lifetime (seconds) — single-use, kept short. */
		@Min(30)
		private long authCodeTtlSeconds = 120;
		/** Refresh-token lifetime (days). */
		@Min(1)
		private long refreshTokenTtlDays = 30;
	}

	@Getter
	@Setter
	public static class Storage {
		private String endpoint = "http://localhost:9000";
		private String accessKey = "";
		private String secretKey = "";
		private String bucket = "hinata";
		private String region = "us-east-1";
		/** Max size of a single uploaded file in megabytes. */
		@Min(1)
		private int maxUploadMb = 25;
		/** Max number of files accepted in one upload batch / request. */
		@Min(1)
		private int maxFilesPerRequest = 10;
		/** Max aggregate size of one upload batch / request in megabytes. */
		@Min(1)
		private int maxRequestMb = 100;
		/**
		 * Grace period, in hours, before an unreferenced inline-Markdown image
		 * (under the {@code media/} prefix) is swept from the bucket. The grace
		 * window protects images that were just uploaded but whose content has not
		 * been saved yet. {@code 0} disables the orphan sweep entirely.
		 */
		@Min(0)
		private int mediaOrphanGraceHours = 24;
		private List<String> allowedContentTypes = List.of(
				// image/svg+xml intentionally excluded: SVG can carry inline
				// JavaScript (stored-XSS risk if ever rendered inline).
				"image/png", "image/jpeg", "image/gif", "image/webp",
				"application/pdf", "text/plain", "text/csv", "application/zip",
				"application/json", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
	}
}
