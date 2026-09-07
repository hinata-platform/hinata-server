package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.common.ApprovalPeriodConsistent;
import com.ahmadre.hinata.common.TimePolicy;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
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

	/**
	 * {@code @Valid} is what makes the constraints inside {@link General} run at
	 * all: Jakarta Validation does not descend into a nested object unless the
	 * field says so, so without it {@code @LogoUrl} is declared and never
	 * evaluated — and {@code /api/v1/meta} republishes whatever was stored to
	 * every unauthenticated client.
	 */
	@Valid
	private General general = new General();
	private App app = new App();
	private Smtp smtp = new Smtp();
	/**
	 * Security policy overrides. Not initialised, for the same reason
	 * {@link #timeTracking} is not: every field means "null ⇒ the environment
	 * decides", so the only way {@link AdminSettingsController#update} can tell
	 * "the caller left this section out of its whole-document PUT" from "the
	 * caller cleared a field" is for the absent case to arrive as null. With an
	 * initialiser it arrives as a fresh all-null block instead, and the stored
	 * lockout, session lifetime and rate-limit switch are quietly replaced by the
	 * environment defaults.
	 */
	private Security security;
	private Oidc oidc = new Oidc();
	private OAuth2 oauth2 = new OAuth2();
	private Saml saml = new Saml();
	private Ldap ldap = new Ldap();
	private Kerberos kerberos = new Kerberos();
	private Cas cas = new Cas();
	private EmailIngest emailIngest = new EmailIngest();
	private GitIntegration gitIntegration = new GitIntegration();
	/** MCP overrides. Not initialised — see {@link #security} for why. */
	private Mcp mcp;
	/**
	 * Time-tracking policy overrides. Deliberately <em>not</em> initialised: an
	 * admin client that does not know this block leaves it out of its whole-document
	 * PUT, and {@link AdminSettingsController#update} can only tell "the caller
	 * omitted it, keep what is stored" from "the caller cleared it" if the absent
	 * case arrives as null.
	 */
	@Valid
	private TimeTracking timeTracking;
	private Audit audit = new Audit();

	@LastModifiedDate
	private Instant updatedAt;

	/**
	 * Implemented by every identity-provider block, so
	 * {@link com.ahmadre.hinata.auth.sso.SsoProfileMapper} can read the profile
	 * mapping of any provider through one contract. Each block ships the defaults
	 * that suit its protocol; an admin overrides them per provider in the admin
	 * area. Syntax of an attribute field: comma-separated alternatives (first
	 * non-blank wins), spaces inside one alternative join its parts.
	 */
	public interface AttributeMapping {
		String getEmailAttribute();

		String getDisplayNameAttribute();

		String getTitleAttribute();

		/**
		 * Whether the directory stays the source of truth for display name and
		 * position on <em>every</em> login. When off, an SSO profile is filled on
		 * first provisioning only (blank fields are still backfilled). Either way an
		 * attribute the IdP omits never clears a stored value.
		 */
		boolean isSyncProfileOnLogin();
	}

	/** General organization settings. */
	@Data
	public static class General {
		/**
		 * The organization logo: either an absolute {@code http(s)} URL or the
		 * internal proxy path an upload sets (see {@link OrganizationLogoService}).
		 * {@code /api/v1/meta} publishes this value to <em>unauthenticated</em>
		 * clients, so it is constrained on write rather than trusted on read — a
		 * stored {@code javascript:} or {@code data:text/html} value would become a
		 * client-side injection the moment any surface dereferenced it directly.
		 */
		@Size(max = 2048, message = "error.logo.invalidUrl")
		@LogoUrl
		private String logoUrl;

		/**
		 * Whether the configured logo can also be drawn into e-mails and exported
		 * documents, or only shown in the app. Read-only: filled on the way out by
		 * {@link AdminSettingsController}, never stored.
		 *
		 * <p>False for a vector — no mail client renders SVG and this process has no
		 * decoder for one — and for a URL that cannot be fetched at all. The admin
		 * area says so, because otherwise the logo simply appears in some places and
		 * not others and that reads as a bug.
		 */
		@Transient
		private boolean logoUsableForDocuments;
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
		/** Microsoft Store listing the Windows app links to when an update is required. */
		private String windowsStoreUrl;
		/** Where the Linux app sends a user who has to update — Flathub, a download page, … */
		private String linuxStoreUrl;
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
		/**
		 * From display name. Null/blank means "use the organization name" — a
		 * fresh instance should mail as the organization, not as the software it
		 * happens to run on (see SmtpMailSenderProvider.fromName()).
		 */
		private String fromName;
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

	/** OpenID Connect (e.g. Synology SSO, Keycloak, Authentik). */
	@Data
	public static class Oidc implements AttributeMapping {
		private boolean enabled = false;
		private String displayName = "OpenID Connect";
		private String issuerUri;
		private String clientId;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String clientSecret;
		private String scopes = "openid,profile,email";
		// Standard OIDC claims only. Providers that ship their own (Synology's
		// "username", Authentik's "job_title") are opt-in: an admin adds the name
		// here rather than us guessing per vendor.
		private String emailAttribute = "email";
		private String displayNameAttribute = "name,preferred_username,given_name family_name,nickname";
		private String titleAttribute = "title,job_title,jobTitle,position";
		private boolean syncProfileOnLogin = true;
	}

	/** Plain OAuth2 provider without OIDC discovery. */
	@Data
	public static class OAuth2 implements AttributeMapping {
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
		private String emailAttribute = "email";
		private String displayNameAttribute = "name,preferred_username,given_name family_name,nickname";
		private String titleAttribute = "title,job_title,jobTitle,position";
		private boolean syncProfileOnLogin = true;
	}

	@Data
	public static class Saml implements AttributeMapping {
		private boolean enabled = false;
		private String displayName = "SAML";
		/** IdP metadata URL (preferred) – e.g. Synology SSO metadata endpoint. */
		private String idpMetadataUri;
		private String entityId;
		// Friendly names plus the X.500 OIDs assertions commonly use instead.
		private String emailAttribute = "email,mail,urn:oid:0.9.2342.19200300.100.1.3";
		private String displayNameAttribute =
				"displayName,cn,name,urn:oid:2.16.840.1.113730.3.1.241,givenName sn";
		private String titleAttribute = "title,jobTitle,urn:oid:2.5.4.12";
		private boolean syncProfileOnLogin = true;
	}

	@Data
	public static class Ldap implements AttributeMapping {
		private boolean enabled = false;
		private String url; // ldap(s)://host:389
		private String baseDn;
		private String managerDn;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String managerPassword;
		private String userSearchBase = "ou=people";
		private String userSearchFilter = "(uid={0})";
		private String emailAttribute = "mail";
		private String displayNameAttribute = "cn,displayName,givenName sn";
		/** {@code description} carries the position in Synology/DSM directories. */
		private String titleAttribute = "title,description";
		private boolean syncProfileOnLogin = true;
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
	 * Time-tracking policy overrides. Every field is a nullable wrapper: null
	 * means "no opinion here — use the environment default", exactly the pattern
	 * {@link App#localAuthEnabled} follows. That is what makes a
	 * {@code server_settings} document written before this block existed fall
	 * back to the configured defaults rather than to {@code false} for
	 * everything, and it is what "Use the environment default" writes when an
	 * admin clears a field.
	 *
	 * <p>The effective values are resolved by
	 * {@code timetracking.TimeTrackingSettings}, which caches them; nothing reads
	 * this block directly to decide anything.
	 *
	 * <p>The policies that could be turned on a person rather than on the work
	 * default to off in the environment (§ 87 Abs. 1 Nr. 6 BetrVG — a facility
	 * suited to monitoring conduct or performance is co-determined before it
	 * runs), and the admin area says so next to each switch.
	 */
	@Data
	public static class TimeTracking {

		/** Master switch for the extended module; null ⇒ {@code hinata.time-tracking.advanced-enabled}. */
		private Boolean advancedEnabled;

		/** Which fields an entry must carry; null fields ⇒ env defaults. */
		@Valid
		private RequiredFields requiredFields;

		/** Entries on or before this day are frozen; null ⇒ env default. */
		private LocalDate lockBefore;

		/** Rounding of reported durations; null ⇒ env default. */
		@Valid
		private Rounding rounding;

		/** Only administrator-defined tags may be used; null ⇒ env default. */
		private Boolean limitTagAccess;

		/** What {@code billable} becomes when an entry does not say; null ⇒ env default. */
		private Boolean defaultBillable;

		/** Rates, costs and invoices; null ⇒ env default. */
		private Boolean billingEnabled;

		/** ISO-4217 code for billing figures; null/blank ⇒ env default. */
		@Size(max = 3, message = "error.timeTracking.currencyInvalid")
		private String currency;

		/** Whether a lead sees member entries with the person attached; null ⇒ env default. */
		private Boolean leadsSeeMemberEntries;

		/** Timesheet submission and approval; null ⇒ env default. */
		private Boolean approvalsEnabled;

		/** The submission rhythm; null fields ⇒ env defaults, and nothing configured ⇒ monthly. */
		@Valid
		private ApprovalPeriod approvalPeriod;

		/** Workload reports (booked against capacity); null ⇒ env default. */
		private Boolean workloadReportsEnabled;

		/** Budget and estimate alerts to leads; null ⇒ env default. */
		private Boolean alertsEnabled;

		/** Personal target reminders; null ⇒ env default. */
		private Boolean targetRemindersEnabled;

		/** Working-hours-act self-hints; null ⇒ env default. */
		private Boolean arbzgHintsEnabled;

		/** How long entries and their free text are kept; null ⇒ env default. */
		@Valid
		private Retention retention;

		/**
		 * The privacy notice shown before the module is first used (stage 8
		 * displays it). Capped because it is free text an admin types once and
		 * every client then downloads with the settings.
		 */
		@Size(max = 20000, message = "error.timeTracking.privacyNoticeTooLong")
		private String privacyNotice;

		/** Subscribing to external calendars; null ⇒ env default. */
		private Boolean icsImportEnabled;

		/**
		 * What is actually in force right now — every field resolved, nothing
		 * null except where null is itself the answer (no lock date, no anchor).
		 *
		 * <p>Read-only and never stored, the way {@code GitIntegration}'s
		 * {@code githubConfigured} is: the admin area needs to <em>show</em> the
		 * effective value, and the obvious way to do that — filling the stored
		 * fields in before sending them — would be a trap. The client hands the
		 * whole document back on save, so the first admin who opened this page
		 * and pressed save would have written every environment default into the
		 * database as an explicit override. The instance would then be deaf to
		 * its own {@code HINATA_TIME_TRACKING_*} variables forever, and "use the
		 * environment default" in the UI would be a button that undoes itself on
		 * the next read.
		 *
		 * <p>That matters most for the one policy that has to be able to go back:
		 * an operator who disables the module fleet-wide after a works-council
		 * objection needs every instance to follow, including the ones an admin
		 * once visited.
		 *
		 * <p>Filled by the module itself ({@code timetracking}), because it owns
		 * the resolution; {@code setup} must not reach into it.
		 */
		@Transient
		@JsonProperty(access = JsonProperty.Access.READ_ONLY)
		private TimeTracking effective;

		/** Per-field overrides for the required-field policy. */
		@Data
		public static class RequiredFields {
			private Boolean project;
			private Boolean issue;
			private Boolean description;
			private Boolean tag;
		}

		/** Rounding override — a reporting parameter, never a rewrite of the entry. */
		@Data
		public static class Rounding {
			private TimePolicy.Rounding mode;
			@Min(value = 1, message = "error.timeTracking.roundingIncrementInvalid")
			@Max(value = 60, message = "error.timeTracking.roundingIncrementInvalid")
			private Integer increment;
		}

		/**
		 * The submission rhythm override. Validated as a whole: {@code days}
		 * belongs to CUSTOM_DAYS and nothing else, and BIWEEKLY/CUSTOM_DAYS need
		 * an anchor to count from. Absent altogether ⇒ the env default, which is
		 * monthly out of the box.
		 */
		@Data
		@ApprovalPeriodConsistent
		public static class ApprovalPeriod implements ApprovalPeriodConsistent.Period {
			private TimePolicy.ApprovalPeriod type;
			private DayOfWeek weekStartsOn;
			private LocalDate anchorDate;
			@Min(value = 1, message = "error.timeTracking.approvalPeriodInvalid")
			@Max(value = 366, message = "error.timeTracking.approvalPeriodInvalid")
			private Integer days;
		}

		/**
		 * Retention in months from the entry's day; {@code 0} keeps data
		 * indefinitely. {@code descriptionPurgeMonths} empties the free text on a
		 * <em>deleted</em> user's entries only; {@code entryPurgeMonths} removes
		 * entries outright, for everyone. See
		 * {@code HinataProperties.TimeTracking.Retention} for why they differ.
		 */
		@Data
		public static class Retention {
			@Min(value = 0, message = "error.timeTracking.retentionInvalid")
			private Integer descriptionPurgeMonths;
			@Min(value = 0, message = "error.timeTracking.retentionInvalid")
			private Integer entryPurgeMonths;
		}
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
