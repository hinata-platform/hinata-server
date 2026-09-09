package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.git.GitIntegrationSettings;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.util.List;
import java.util.Map;

/**
 * Admin area: read and update runtime server settings (SSO, e-mail ingest,
 * push). Secured by the /api/v1/admin/** ADMIN rule in SecurityConfig.
 */
@Slf4j
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

	private final SettingsService settings;
	private final HinataProperties properties;
	private final FeatureFlags featureFlags;
	/** Modules adding their own derived, read-only values; see {@link SettingsPrefill}. */
	private final List<SettingsPrefill> modulePrefills;
	/** Modules describing what an admin changed about them; see {@link SettingsAudit}. */
	private final List<SettingsAudit> moduleAudits;
	private final GitIntegrationSettings gitConfig;
	private final AuditService audit;
	private final CurrentUser currentUser;
	private final com.ahmadre.hinata.auth.SecurityPolicy securityPolicy;
	private final OrganizationLogoService logoService;
	private final BrandLogoService brandLogo;

	@GetMapping
	public ServerSettings get() {
		ServerSettings current = settings.get();
		fillLogoReach(current);
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
		if (isBlank(app.getWindowsStoreUrl())) {
			app.setWindowsStoreUrl(defaults.getWindowsStoreUrl());
		}
		if (isBlank(app.getLinuxStoreUrl())) {
			app.setLinuxStoreUrl(defaults.getLinuxStoreUrl());
		}
		// The editable flag map: env defaults with the admin overrides on top, so a
		// newly shipped default flag surfaces in the editor even after admins have
		// toggled other, unrelated flags. Module flags (mcp, advanced_time_tracking)
		// are deliberately absent — each has its own switch in its own section.
		app.setFeatureFlags(featureFlags.configurable());
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
		// The modules fill in what only they can resolve. Note the difference
		// from the prefills above: those write into stored fields, so a save
		// freezes them into the database. A module writes into a read-only view
		// instead, and the operator's environment keeps deciding.
		modulePrefills.forEach(prefill -> prefill.prefill(current));
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

	/**
	 * Tells the admin form whether the configured logo reaches mails and exported
	 * documents too, or only the app. Cached in {@link BrandLogoService}, so this
	 * costs nothing on a warm instance.
	 */
	private void fillLogoReach(ServerSettings current) {
		String url = current.getGeneral().getLogoUrl();
		boolean configured = url != null && !url.isBlank();
		current.getGeneral().setLogoUsableForDocuments(
				configured && !brandLogo.configuredButUnusableForDocuments());
	}

	@PutMapping
	public ServerSettings update(@Valid @RequestBody ServerSettings updated) {
		ServerSettings current = settings.get();
		// Setup completion and org identity are managed by the setup flow only.
		updated.setSetupCompleted(current.isSetupCompleted());
		if (isBlank(updated.getOrganizationName())) {
			updated.setOrganizationName(current.getOrganizationName());
		}
		keepSecretsIfBlank(updated, current);
		// The PUT is a whole-document write. The published 10.3.3 client keeps the
		// settings as the raw map it read, so it hands sections it does not know
		// back untouched — but a deployment script, a curl body or any client
		// built from a typed model omits what it does not model. For these three
		// blocks every field means "null ⇒ the environment decides", so an
		// omission would not be stored as an omission: it would be resolved to the
		// environment default and take effect. MCP would switch itself back on, an
		// administrator's lockout and session limits would revert, and the module
		// would leave. An omitted block therefore means "no opinion", not "erase
		// what is stored".
		if (updated.getTimeTracking() == null) {
			updated.setTimeTracking(current.getTimeTracking());
		}
		if (updated.getMcp() == null) {
			updated.setMcp(current.getMcp());
		}
		if (updated.getSecurity() == null) {
			updated.setSecurity(current.getSecurity());
		}
		// An upload the admin has just switched away from: the stored object would
		// otherwise shadow the new URL in the /meta/logo proxy and linger as an
		// orphan. Only relevant when there *was* an upload — testing the incoming
		// value instead would fire on every ordinary save (an external URL and a
		// blank one are both "not internal"), and on an instance without object
		// storage that turned the whole settings PUT into a 503.
		boolean droppingUpload = OrganizationLogoService.isInternal(current.getGeneral().getLogoUrl())
				&& (updated.getGeneral() == null
						|| !OrganizationLogoService.isInternal(updated.getGeneral().getLogoUrl()));
		// Recorded before the save so that disabling audit logging itself is still
		// captured (the check reads the pre-save, still-enabled settings).
		User actor = currentUser.require();
		audit.event(AuditAction.SETTINGS_CHANGED)
				.actor(actor)
				// An explicit "audit": null in the body is a 500 otherwise, and the
				// settings save it was recording never happens.
				.meta("auditEnabled", String.valueOf(
						updated.getAudit() != null && updated.getAudit().isEnabled()))
				.log();
		// The modules' own word on what moved, recorded here for the same reason
		// and at the same moment: a description of a change that is only written
		// once the change has succeeded cannot describe the change that switched
		// the recording off. A module that fails to describe its own settings
		// never fails the save.
		for (SettingsAudit moduleAudit : moduleAudits) {
			try {
				moduleAudit.record(current, updated, actor);
			}
			catch (RuntimeException ex) {
				log.warn("Settings audit hook {} failed: {}",
						moduleAudit.getClass().getSimpleName(), ex.toString());
			}
		}
		if (updated.getAudit() == null) {
			updated.setAudit(current.getAudit());
		}
		ServerSettings saved = settings.save(updated);
		// After the save: a failed write must not leave the settings pointing at an
		// object that is already gone.
		if (droppingUpload) {
			logoService.deleteStoredObject();
		}
		return saved;
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
