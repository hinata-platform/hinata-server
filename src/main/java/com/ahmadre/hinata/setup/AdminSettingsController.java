package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.git.GitIntegrationSettings;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin area: read and update runtime server settings (SSO, e-mail ingest,
 * push). Secured by the /api/v1/admin/** ADMIN rule in SecurityConfig.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

	private final SettingsService settings;
	private final HinataProperties properties;
	private final GitIntegrationSettings gitConfig;
	private final AuditService audit;
	private final CurrentUser currentUser;

	@GetMapping
	public ServerSettings get() {
		ServerSettings current = settings.get();
		// Surface the effective app config (env defaults when not yet overridden)
		// so the admin form pre-fills the values currently served via /meta.
		ServerSettings.App app = current.getApp();
		HinataProperties.App defaults = properties.getApp();
		if (isBlank(app.getMinVersion())) {
			app.setMinVersion(defaults.getMinVersion());
		}
		if (isBlank(app.getPrivacyPolicyUrl())) {
			app.setPrivacyPolicyUrl(defaults.getPrivacyPolicyUrl());
		}
		if (isBlank(app.getIosStoreUrl())) {
			app.setIosStoreUrl(defaults.getIosStoreUrl());
		}
		if (isBlank(app.getAndroidStoreUrl())) {
			app.setAndroidStoreUrl(defaults.getAndroidStoreUrl());
		}
		if (isBlank(app.getMacosStoreUrl())) {
			app.setMacosStoreUrl(defaults.getMacosStoreUrl());
		}
		if (app.getFeatureFlags() == null || app.getFeatureFlags().isEmpty()) {
			app.setFeatureFlags(new java.util.LinkedHashMap<>(defaults.getFeatureFlags()));
		}
		// Surface the effective auth toggles so the switches reflect the value
		// currently in force (env default until an admin overrides it).
		if (app.getLocalAuthEnabled() == null) {
			app.setLocalAuthEnabled(defaults.isLocalAuthEnabled());
		}
		if (app.getRegistrationEnabled() == null) {
			app.setRegistrationEnabled(defaults.isRegistrationEnabled());
		}
		if (app.getRequireAdminApproval() == null) {
			app.setRequireAdminApproval(defaults.isRequireAdminApproval());
		}
		prefillGitIntegration(current);
		return current;
	}

	/**
	 * Pre-fill the Git integration non-secret fields from the env defaults (so the
	 * form shows the currently-effective values) and surface the derived, read-only
	 * status flags the admin UI uses to show whether a provider is live or emulated.
	 * Secrets are WRITE_ONLY and deliberately never echoed.
	 */
	private void prefillGitIntegration(ServerSettings current) {
		ServerSettings.GitIntegration git = current.getGitIntegration();
		HinataProperties.GitIntegration gitDefaults = properties.getGitIntegration();
		if (isBlank(git.getGithubClientId())) {
			git.setGithubClientId(gitDefaults.getGithubClientId());
		}
		if (isBlank(git.getGitlabClientId())) {
			git.setGitlabClientId(gitDefaults.getGitlabClientId());
		}
		if (isBlank(git.getBitbucketClientId())) {
			git.setBitbucketClientId(gitDefaults.getBitbucketClientId());
		}
		if (isBlank(git.getWebhookBaseUrl())) {
			git.setWebhookBaseUrl(gitDefaults.getWebhookBaseUrl());
		}
		git.setGithubConfigured(gitConfig.configured("github"));
		git.setGitlabConfigured(gitConfig.configured("gitlab"));
		git.setBitbucketConfigured(gitConfig.configured("bitbucket"));
		git.setTokenSecretConfigured(gitConfig.tokenSecretConfigured());
	}

	@PutMapping
	public ServerSettings update(@RequestBody ServerSettings updated) {
		ServerSettings current = settings.get();
		// Setup completion and org identity are managed by the setup flow only.
		updated.setSetupCompleted(current.isSetupCompleted());
		if (isBlank(updated.getOrganizationName())) {
			updated.setOrganizationName(current.getOrganizationName());
		}
		keepSecretsIfBlank(updated, current);
		// Recorded before the save so that disabling audit logging itself is still
		// captured (the check reads the pre-save, still-enabled settings).
		audit.event(AuditAction.SETTINGS_CHANGED)
				.actor(currentUser.require())
				.meta("auditEnabled", String.valueOf(updated.getAudit().isEnabled()))
				.log();
		return settings.save(updated);
	}

	/** WRITE_ONLY secrets are not echoed back; keep stored values when omitted. */
	private void keepSecretsIfBlank(ServerSettings updated, ServerSettings current) {
		if (isBlank(updated.getOidc().getClientSecret())) {
			updated.getOidc().setClientSecret(current.getOidc().getClientSecret());
		}
		if (isBlank(updated.getOauth2().getClientSecret())) {
			updated.getOauth2().setClientSecret(current.getOauth2().getClientSecret());
		}
		if (isBlank(updated.getLdap().getManagerPassword())) {
			updated.getLdap().setManagerPassword(current.getLdap().getManagerPassword());
		}
		if (isBlank(updated.getEmailIngest().getPassword())) {
			updated.getEmailIngest().setPassword(current.getEmailIngest().getPassword());
		}
		if (isBlank(updated.getSmtp().getPassword())) {
			updated.getSmtp().setPassword(current.getSmtp().getPassword());
		}
		keepGitSecretsIfBlank(updated, current);
	}

	/** Git client secrets + token secret are WRITE_ONLY; keep stored on blank. */
	private void keepGitSecretsIfBlank(ServerSettings updated, ServerSettings current) {
		ServerSettings.GitIntegration git = updated.getGitIntegration();
		if (git == null) {
			git = new ServerSettings.GitIntegration();
			updated.setGitIntegration(git);
		}
		ServerSettings.GitIntegration curGit = current.getGitIntegration() != null
				? current.getGitIntegration()
				: new ServerSettings.GitIntegration();
		if (isBlank(git.getGithubClientSecret())) {
			git.setGithubClientSecret(curGit.getGithubClientSecret());
		}
		if (isBlank(git.getGitlabClientSecret())) {
			git.setGitlabClientSecret(curGit.getGitlabClientSecret());
		}
		if (isBlank(git.getBitbucketClientSecret())) {
			git.setBitbucketClientSecret(curGit.getBitbucketClientSecret());
		}
		if (isBlank(git.getTokenSecret())) {
			git.setTokenSecret(curGit.getTokenSecret());
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
