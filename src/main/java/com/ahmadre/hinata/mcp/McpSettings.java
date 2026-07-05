package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the <em>effective</em> MCP configuration. An administrator's runtime
 * overrides (stored on {@link ServerSettings}) take precedence over the
 * environment-driven {@link HinataProperties.Mcp} defaults — the same
 * DB-overrides-ENV pattern the {@link com.ahmadre.hinata.git.GitIntegrationSettings}
 * and the {@code /meta} app settings use. Every consumer (the {@code /mcp}
 * transport gate, the PAT UI feature flag and the admin status readout) reads
 * through here so there is a single source of truth.
 */
@Component
@RequiredArgsConstructor
public class McpSettings {

	private final SettingsService settings;
	private final HinataProperties properties;

	/** Effective feature master switch (DB override, else env default). */
	public boolean enabled() {
		Boolean override = db().getEnabled();
		return override != null ? override : env().isEnabled();
	}

	/** Effective max active Personal Access Tokens per user. */
	public int maxPatsPerUser() {
		Integer override = db().getMaxPatsPerUser();
		return override != null ? override : env().getMaxPatsPerUser();
	}

	private ServerSettings.Mcp db() {
		ServerSettings.Mcp m = settings.get().getMcp();
		return m != null ? m : new ServerSettings.Mcp();
	}

	private HinataProperties.Mcp env() {
		return properties.getMcp();
	}
}
