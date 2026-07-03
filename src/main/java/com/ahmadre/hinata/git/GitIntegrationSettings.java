package com.ahmadre.hinata.git;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the <em>effective</em> Git-integration configuration. An
 * administrator's runtime overrides (stored on {@link ServerSettings}) take
 * precedence over the environment-driven {@link HinataProperties.GitIntegration}
 * defaults — the same DB-overrides-ENV pattern the {@code /meta} app settings
 * use. Every consumer (OAuth flow in {@link GitService}, token encryption in
 * {@link TokenCipher}, and the admin status readout) reads through here so there
 * is a single source of truth.
 */
@Component
@RequiredArgsConstructor
public class GitIntegrationSettings {

	/** The out-of-the-box placeholder; treated as "no real secret configured". */
	static final String DEFAULT_TOKEN_SECRET = "change-me-change-me-change-me-git-tokens";

	private final SettingsService settings;
	private final HinataProperties properties;

	public String githubClientId() {
		return resolve(db().getGithubClientId(), env().getGithubClientId());
	}

	public String githubClientSecret() {
		return resolve(db().getGithubClientSecret(), env().getGithubClientSecret());
	}

	public String gitlabClientId() {
		return resolve(db().getGitlabClientId(), env().getGitlabClientId());
	}

	public String gitlabClientSecret() {
		return resolve(db().getGitlabClientSecret(), env().getGitlabClientSecret());
	}

	public String bitbucketClientId() {
		return resolve(db().getBitbucketClientId(), env().getBitbucketClientId());
	}

	public String bitbucketClientSecret() {
		return resolve(db().getBitbucketClientSecret(), env().getBitbucketClientSecret());
	}

	public String webhookBaseUrl() {
		return resolve(db().getWebhookBaseUrl(), env().getWebhookBaseUrl());
	}

	public String tokenSecret() {
		return resolve(db().getTokenSecret(), env().getTokenSecret());
	}

	public String clientId(String provider) {
		return switch (provider) {
			case "github" -> githubClientId();
			case "gitlab" -> gitlabClientId();
			case "bitbucket" -> bitbucketClientId();
			default -> "";
		};
	}

	public String clientSecret(String provider) {
		return switch (provider) {
			case "github" -> githubClientSecret();
			case "gitlab" -> gitlabClientSecret();
			case "bitbucket" -> bitbucketClientSecret();
			default -> "";
		};
	}

	/**
	 * A provider is <em>live</em> (real OAuth broker) only when BOTH its client id
	 * and secret are set; otherwise the flow runs emulated.
	 */
	public boolean configured(String provider) {
		return !isBlank(clientId(provider)) && !isBlank(clientSecret(provider));
	}

	/** Whether a non-placeholder token secret is in effect. */
	public boolean tokenSecretConfigured() {
		String secret = tokenSecret();
		return !isBlank(secret) && !DEFAULT_TOKEN_SECRET.equals(secret);
	}

	/**
	 * Public base URL of this server's API that providers can reach (OAuth
	 * redirect + webhooks). Uses the admin-set webhook base URL (e.g.
	 * {@code https://<ngrok>/api/v1}); falls back to {@code <baseUrl>/api/v1}.
	 */
	public String publicApiBase() {
		String hook = webhookBaseUrl();
		if (!isBlank(hook)) {
			return stripSlash(hook);
		}
		return stripSlash(properties.getBaseUrl()) + "/api/v1";
	}

	/** The single OAuth redirect/callback URL — must match the provider app. */
	public String oauthRedirectUri() {
		return publicApiBase() + "/git/oauth/callback";
	}

	private static String stripSlash(String url) {
		if (url == null) {
			return "";
		}
		String trimmed = url.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

	private ServerSettings.GitIntegration db() {
		ServerSettings.GitIntegration g = settings.get().getGitIntegration();
		return g != null ? g : new ServerSettings.GitIntegration();
	}

	private HinataProperties.GitIntegration env() {
		return properties.getGitIntegration();
	}

	private static String resolve(String preferred, String fallback) {
		return preferred != null && !preferred.isBlank() ? preferred : fallback;
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
