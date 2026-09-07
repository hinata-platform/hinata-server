package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.common.FeatureFlags;
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
 *
 * <p>It is also the {@link FeatureFlags.Module} behind the {@code mcp} client
 * flag, so what {@code /meta} publishes and what the transport enforces are one
 * value read one way.
 */
@Component
@RequiredArgsConstructor
public class McpSettings implements FeatureFlags.Module {

	/** The client-visible flag name; snake_case like every other platform flag. */
	public static final String FLAG = "mcp";

	private final SettingsService settings;
	private final HinataProperties properties;

	@Override
	public String flagKey() {
		return FLAG;
	}

	@Override
	public boolean flagEnabled() {
		return enabled();
	}

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
