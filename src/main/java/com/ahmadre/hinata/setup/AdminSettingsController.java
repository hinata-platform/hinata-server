package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.git.GitIntegrationSettings;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

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
	private final com.ahmadre.hinata.auth.SecurityPolicy securityPolicy;
	private final OrganizationLogoService logoService;

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
		// Merge env defaults with any admin overrides (override wins per-key) so a
		// newly shipped default flag (e.g. a fresh feature) surfaces in the editor
		// even after admins have already toggled other, unrelated flags — matching
		// the effective-flags merge in MetaController#meta().
		java.util.Map<String, Boolean> mergedFlags = new java.util.LinkedHashMap<>(defaults.getFeatureFlags());
		if (app.getFeatureFlags() != null) {
			mergedFlags.putAll(app.getFeatureFlags());
		}
		app.setFeatureFlags(mergedFlags);
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
		prefillMcp(current);
		prefillSecurity(current);
		return current;
	}

	/**
	 * Pre-fill the security policy from the effective values so the admin form shows
	 * the values currently in force (env default until an admin overrides them). The
	 * source of truth is {@link com.ahmadre.hinata.auth.SecurityPolicy}, so a blank
	 * field always displays exactly what enforcement uses.
	 */
	private void prefillSecurity(ServerSettings current) {
		ServerSettings.Security sec = current.getSecurity();
		if (sec == null) {
			sec = new ServerSettings.Security();
			current.setSecurity(sec);
		}
		if (sec.getPasswordMinLength() == null) {
			sec.setPasswordMinLength(securityPolicy.passwordMinLength());
		}
		if (sec.getMaxLoginAttempts() == null) {
			sec.setMaxLoginAttempts(securityPolicy.maxLoginAttempts());
		}
		if (sec.getLockoutMinutes() == null) {
			sec.setLockoutMinutes(securityPolicy.lockoutMinutes());
		}
		if (sec.getSessionLifetimeHours() == null) {
			sec.setSessionLifetimeHours(securityPolicy.sessionLifetimeHours());
		}
		if (sec.getRateLimitEnabled() == null) {
			sec.setRateLimitEnabled(securityPolicy.rateLimitEnabled());
		}
	}

	/**
	 * Pre-fill the MCP overrides from the env defaults so the admin form shows the
	 * currently-effective values (env default until an admin overrides them). MCP
	 * has no secrets, so this is a plain env prefill — no keep-secret logic.
	 */
	private void prefillMcp(ServerSettings current) {
		ServerSettings.Mcp mcp = current.getMcp();
		if (mcp == null) {
			mcp = new ServerSettings.Mcp();
			current.setMcp(mcp);
		}
		HinataProperties.Mcp mcpDefaults = properties.getMcp();
		if (mcp.getEnabled() == null) {
			mcp.setEnabled(mcpDefaults.isEnabled());
		}
		if (mcp.getMaxPatsPerUser() == null) {
			mcp.setMaxPatsPerUser(mcpDefaults.getMaxPatsPerUser());
		}
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
		// A settings PUT that carries an external/blank logo URL means the admin is
		// no longer using an uploaded logo — drop the stored object so it can't
		// shadow the URL in the /meta/logo proxy and doesn't linger as an orphan.
		if (updated.getGeneral() == null
				|| !OrganizationLogoService.isInternal(updated.getGeneral().getLogoUrl())) {
			logoService.deleteStoredObject();
		}
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

	/**
	 * Uploads an organization logo into object storage and points
	 * {@code General.logoUrl} at the internal proxy. The alternative to typing a
	 * URL — either way {@code /meta/logo} serves the image same-origin.
	 */
	@PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, String> uploadLogo(@RequestParam("file") MultipartFile file) {
		String url = logoService.store(file);
		audit.event(AuditAction.SETTINGS_CHANGED)
				.actor(currentUser.require())
				.meta("logo", "uploaded")
				.log();
		return Map.of("logoUrl", url);
	}

	/** Removes an uploaded logo (deletes the object, clears the internal URL). */
	@DeleteMapping("/logo")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteLogo() {
		logoService.remove();
		audit.event(AuditAction.SETTINGS_CHANGED)
				.actor(currentUser.require())
				.meta("logo", "removed")
				.log();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
