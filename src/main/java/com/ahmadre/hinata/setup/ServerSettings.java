package com.ahmadre.hinata.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton document (id = "server") holding everything an administrator can
 * configure at runtime through the in-app admin area: organization branding,
 * SSO providers, e-mail ingestion and push notifications.
 */
@Data
@Document("server_settings")
public class ServerSettings {

	public static final String SINGLETON_ID = "server";

	@Id
	private String id = SINGLETON_ID;

	private boolean setupCompleted = false;

	private String organizationName;

	private General general = new General();
	private App app = new App();
	private Smtp smtp = new Smtp();
	private Security security = new Security();
	private Moderation moderation = new Moderation();
	private Oidc oidc = new Oidc();
	private OAuth2 oauth2 = new OAuth2();
	private Saml saml = new Saml();
	private Ldap ldap = new Ldap();
	private Kerberos kerberos = new Kerberos();
	private Cas cas = new Cas();
	private EmailIngest emailIngest = new EmailIngest();
	private GitIntegration gitIntegration = new GitIntegration();
	private Mcp mcp = new Mcp();
	private Audit audit = new Audit();

	@LastModifiedDate
	private Instant updatedAt;

	/** General organization settings. */
	@Data
	public static class General {
		private String logoUrl;
		private String timezone = "Europe/Berlin";
		private String defaultLocale = "de";
	}

	/**
	 * App/client settings served to the Flutter app via {@code /api/v1/meta}.
	 * Admin-configurable at runtime; blank/empty values fall back to the
	 * environment-driven {@code hinata.app.*} defaults.
	 */
	@Data
	public static class App {
		/** Minimum app version; older clients are forced to update. */
		private String minVersion;
		private String privacyPolicyUrl;
		/** App Store listing the iOS app links to when an update is required. */
		private String iosStoreUrl;
		/** Play Store listing the Android app links to when an update is required. */
		private String androidStoreUrl;
		/** Mac App Store listing the macOS app links to when an update is required. */
		private String macosStoreUrl;
		/** Optional client feature flags (name → enabled). */
		private Map<String, Boolean> featureFlags = new LinkedHashMap<>();

		// Nullable so a missing field on an existing document falls back to the
		// env default (see AuthPolicy / MetaController) instead of defaulting false.

		/** Master switch for local email/password auth (sign-in, sign-up, reset). */
		private Boolean localAuthEnabled;

		/** Whether the public self-registration (sign-up) flow is open. */
		private Boolean registrationEnabled;

		/** Require an admin to approve verified self-registrations before sign-in. */
		private Boolean requireAdminApproval;
	}

	/** Outbound SMTP – used for all transactional e-mails. */
	@Data
	public static class Smtp {
		private boolean enabled = false;
		private String host;
		private int port = 587;
		private boolean ssl = false;
		private boolean starttls = true;
		private String username;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String password;
		private String fromAddress = "hinata@localhost";
		private String fromName = "Hinata";
	}

	/**
	 * Basic security hardening knobs, admin-configurable at runtime. Nullable
	 * wrappers so an unset field falls back to the env-driven defaults — the
	 * effective value is resolved by {@link com.ahmadre.hinata.auth.SecurityPolicy}
	 * (DB override wins over env). This block is the single source of truth for the
	 * password/lockout/session/rate-limit policy: every enforcement point reads
	 * through {@code SecurityPolicy}, never a hardcoded constant.
	 */
	@Data
	public static class Security {
		/** Minimum password length; null ⇒ {@code hinata.security.password-min-length}. */
		private Integer passwordMinLength;
		/** Failed logins before a temporary block; null ⇒ {@code hinata.rate-limit.max-login-failures}. */
		private Integer maxLoginAttempts;
		/** Minutes an account stays locked; null ⇒ {@code hinata.rate-limit.login-block-minutes}. */
		private Integer lockoutMinutes;
		/** Session (refresh-token) lifetime in hours; null ⇒ {@code hinata.jwt.refresh-token-seconds}. */
		private Integer sessionLifetimeHours;
		/** Per-IP API rate limiting on/off; null ⇒ {@code hinata.rate-limit.enabled}. */
		private Boolean rateLimitEnabled;
	}

	/**
	 * Runtime moderation policy, admin-configurable. Nullable wrappers so an unset
	 * field falls back to the env-driven defaults — the effective value is resolved
	 * by {@link com.ahmadre.hinata.moderation.ModerationPolicy} (DB override wins
	 * over env), which is also where the floors live that stop a setting from
	 * making the filter useless.
	 *
	 * <h2>What is deliberately absent, and why</h2>
	 *
	 * <p><b>No per-category switch for child sexual content or malware, and no
	 * strict thresholds.</b> Those two categories are non-overridable by
	 * construction — {@code ModerationPolicy.isOverridable} returns false for them,
	 * so they are neither relieved on technical surfaces, nor covered by the
	 * long-form flag-only trade, nor degradable. Exposing
	 * {@code strictBlockThreshold} / {@code strictFlagThreshold} would hand exactly
	 * that guarantee to a web form: an admin — or a compromised admin account —
	 * could set them to 100 and turn a Hinata instance into a safe place to put
	 * either, while every other part of the code carried on claiming they could not
	 * be weakened. They stay env-only so the invariant remains one.
	 *
	 * <p><b>No {@code supportedTypes} for the image sidecar.</b> That list has to
	 * match what the deployed sidecar build actually decodes; it is a property of
	 * the container that is running, not a policy a tenant chooses. An admin who
	 * added {@code image/gif} here would not teach the sidecar to read GIFs, they
	 * would only convert an honest "not judged" into a round trip that ends in a
	 * 415 and a degraded verdict on every animated image.
	 *
	 * <p><b>No {@code freezeRefreshInterval} and no escalation {@code retryDelay}.</b>
	 * Both are infrastructure tuning rather than policy — how often each replica
	 * re-reads the freeze registry, and how long a failed webhook attempt waits.
	 * Neither changes what is moderated or what a verdict means, and the first is
	 * bound to a {@code @Scheduled} placeholder that cannot be re-read at runtime
	 * anyway.
	 */
	@Data
	public static class Moderation {
		/** Master switch; null ⇒ {@code hinata.moderation.enabled}. */
		private Boolean enabled;
		/** Scan user-authored text; null ⇒ {@code hinata.moderation.text-enabled}. */
		private Boolean textEnabled;
		/** Classify uploaded images; null ⇒ {@code hinata.moderation.image-enabled}. */
		private Boolean imageEnabled;
		/** Refusal threshold; null ⇒ {@code hinata.moderation.block-threshold}. */
		private Integer blockThreshold;
		/** Review threshold; null ⇒ {@code hinata.moderation.flag-threshold}. */
		private Integer flagThreshold;
		/** Queue long-form bodies instead of refusing them; null ⇒ env default. */
		private Boolean longFormFlagOnly;
		/** Proceed when an optional tier is unavailable; null ⇒ env default. */
		private Boolean failOpen;

		// ── The two out-of-process tiers ──────────────────────────────────────
		// Settable here because they are the two things an operator most needs to
		// change and the two that used to require editing the container
		// environment and restarting — while the panel sat there reporting that
		// the image tier was not configured and offering no way to fix it.

		/**
		 * Base URL of the image classification sidecar, e.g.
		 * {@code http://hinata-moderation:8081}; blank ⇒
		 * {@code hinata.moderation.image.endpoint}. Changing it re-points the client
		 * without a restart — see {@code HttpImageModerator}.
		 */
		private String imageEndpoint;
		/**
		 * Budget for one call to the sidecar, applied to the connect and the read;
		 * null ⇒ {@code hinata.moderation.image.timeout}. This sits on the upload
		 * path of every attachment, so it is a user-visible wait.
		 */
		private Duration imageTimeout;
		/**
		 * Endpoint the signed freeze notice is POSTed to; blank ⇒
		 * {@code hinata.moderation.escalation.url}. Blank everywhere is a supported
		 * configuration: a small install may genuinely have nobody to notify, and
		 * the freeze happens either way.
		 */
		private String escalationUrl;
		/**
		 * Shared secret for the {@code X-Hinata-Signature} HMAC; blank ⇒
		 * {@code hinata.moderation.escalation.secret}.
		 *
		 * <p>{@code WRITE_ONLY} like every other secret in this document: accepted
		 * on input, never echoed back, and preserved when the panel PUTs it blank
		 * (see {@code AdminSettingsController.keepSecretsIfBlank}). What the UI can
		 * learn about it is {@link #escalationSecretConfigured} and nothing else.
		 */
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String escalationSecret;
		/** Budget for one delivery attempt; null ⇒ {@code hinata.moderation.escalation.timeout}. */
		private Duration escalationTimeout;
		/**
		 * Attempts before one notice is audited as undelivered; null ⇒
		 * {@code hinata.moderation.escalation.max-attempts}.
		 */
		private Integer escalationMaxAttempts;

		// ── Derived, read-only status (never persisted) ───────────────────────
		/**
		 * Whether a signing secret is in effect, from either source. The only thing
		 * the panel can be told about the secret — the same shape as
		 * {@code GitIntegration.tokenSecretConfigured}, for the same reason.
		 */
		@Transient
		@JsonProperty(access = JsonProperty.Access.READ_ONLY)
		private boolean escalationSecretConfigured;
	}

	/** OpenID Connect (e.g. Synology SSO, Keycloak, Authentik). */
	@Data
	public static class Oidc {
		private boolean enabled = false;
		private String displayName = "OpenID Connect";
		private String issuerUri;
		private String clientId;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String clientSecret;
		private String scopes = "openid,profile,email";
	}

	/** Plain OAuth2 provider without OIDC discovery. */
	@Data
	public static class OAuth2 {
		private boolean enabled = false;
		private String displayName = "OAuth 2.0";
		private String clientId;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String clientSecret;
		private String authorizationUri;
		private String tokenUri;
		private String userInfoUri;
		private String userNameAttribute = "email";
		private String scopes = "profile,email";
	}

	@Data
	public static class Saml {
		private boolean enabled = false;
		private String displayName = "SAML";
		/** IdP metadata URL (preferred) – e.g. Synology SSO metadata endpoint. */
		private String idpMetadataUri;
		private String entityId;
	}

	@Data
	public static class Ldap {
		private boolean enabled = false;
		private String url; // ldap(s)://host:389
		private String baseDn;
		private String managerDn;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String managerPassword;
		private String userSearchBase = "ou=people";
		private String userSearchFilter = "(uid={0})";
		private String emailAttribute = "mail";
		private String displayNameAttribute = "cn";
	}

	/** Kerberos/SPNEGO – configuration only; see docs for the required keytab. */
	@Data
	public static class Kerberos {
		private boolean enabled = false;
		private String servicePrincipal;
		private String keytabLocation;
	}

	@Data
	public static class Cas {
		private boolean enabled = false;
		private String serverUrlPrefix;
		private String serviceUrl;
	}

	/** IMAP mailbox that is polled and converted into issues. */
	@Data
	public static class EmailIngest {
		private boolean enabled = false;
		private String host;
		private int port = 993;
		private boolean ssl = true;
		private String username;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String password;
		private String folder = "INBOX";
		/** Project that receives issues created from inbound mail. */
		private String defaultProjectId;
		private int pollSeconds = 60;
	}

	/**
	 * Git integration — provider OAuth-app credentials (GitHub / GitLab /
	 * Bitbucket), the public webhook base URL and the token-encryption secret.
	 * Admin-configurable at runtime; blank values fall back to the env-driven
	 * {@code hinata.git-integration.*} defaults. Client secrets and the token
	 * secret are {@code WRITE_ONLY} (accepted on input, never echoed back). The
	 * {@code *Configured} flags are derived, read-only status surfaced to the
	 * admin UI so it can show whether a provider is live or running emulated.
	 */
	@Data
	public static class GitIntegration {
		private String githubClientId;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String githubClientSecret;
		private String gitlabClientId;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String gitlabClientSecret;
		private String bitbucketClientId;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String bitbucketClientSecret;
		/** Public base URL provider webhooks POST to (e.g. https://…/api/v1). */
		private String webhookBaseUrl;
		/** Key used to encrypt stored provider access tokens at rest (>= 16 chars). */
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String tokenSecret;

		// ── Derived, read-only status (never persisted) ───────────────────────
		/** Effective GitHub app has both a client id and secret → real OAuth. */
		@Transient
		@JsonProperty(access = JsonProperty.Access.READ_ONLY)
		private boolean githubConfigured;
		@Transient
		@JsonProperty(access = JsonProperty.Access.READ_ONLY)
		private boolean gitlabConfigured;
		@Transient
		@JsonProperty(access = JsonProperty.Access.READ_ONLY)
		private boolean bitbucketConfigured;
		/** A non-default token secret is in effect (tokens are protected). */
		@Transient
		@JsonProperty(access = JsonProperty.Access.READ_ONLY)
		private boolean tokenSecretConfigured;
	}

	/**
	 * MCP (Model Context Protocol) server overrides. Nullable wrapper types so a
	 * blank/unset field falls back to the env-driven {@code hinata.mcp.*} defaults
	 * (see {@link com.ahmadre.hinata.mcp.McpSettings}). {@code enabled} gates the
	 * {@code /mcp} transport, the PAT UI feature flag and the exposed tools.
	 */
	@Data
	public static class Mcp {
		/** Master switch override; null ⇒ use the env default. */
		private Boolean enabled;
		/** Max active Personal Access Tokens per user override; null ⇒ env default. */
		private Integer maxPatsPerUser;
	}

	/**
	 * Security audit logging. {@code enabled} is the master switch — when off,
	 * nothing is recorded regardless of {@code events}. {@code events} maps an
	 * {@code AuditAction} name to whether that specific event is captured; a
	 * missing key falls back to the action's built-in default, so newly added
	 * event types are on by default until an admin opts out.
	 */
	@Data
	public static class Audit {
		/** Master on/off switch for all audit logging. */
		private boolean enabled = true;
		/** Per-event-type capture flags, keyed by {@code AuditAction} name. */
		private Map<String, Boolean> events = new LinkedHashMap<>();
		/** Days to keep audit records before the nightly retention sweep drops them. */
		private int retentionDays = 365;
	}
}
