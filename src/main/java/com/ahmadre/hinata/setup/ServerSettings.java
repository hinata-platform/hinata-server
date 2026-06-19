package com.ahmadre.hinata.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

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
	private Oidc oidc = new Oidc();
	private OAuth2 oauth2 = new OAuth2();
	private Saml saml = new Saml();
	private Ldap ldap = new Ldap();
	private Kerberos kerberos = new Kerberos();
	private Cas cas = new Cas();
	private EmailIngest emailIngest = new EmailIngest();
	private Push push = new Push();

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
		/** Optional client feature flags (name → enabled). */
		private Map<String, Boolean> featureFlags = new LinkedHashMap<>();
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

	/** Basic security hardening knobs configurable at runtime. */
	@Data
	public static class Security {
		private int passwordMinLength = 10;
		private int maxLoginAttempts = 5;
		private int lockoutMinutes = 15;
		private int sessionLifetimeHours = 168;
		private boolean rateLimitEnabled = true;
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

	/** Firebase Cloud Messaging for mobile push notifications. */
	@Data
	public static class Push {
		private boolean enabled = false;
		private String fcmProjectId;
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		private String fcmServiceAccountJson;
	}
}
