package com.ahmadre.hinata.template;

import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Whether project templates and relative deadlines exist on this instance.
 *
 * <p>One question, asked in three places that must never disagree: the gate that answers a request
 * ({@link ProjectTemplateGate}), the flag {@code /api/v1/meta} publishes so the app knows whether
 * to offer the menu entry at all, and the services HTTP never reaches — the MCP tools of stage 8
 * and the demo seeder. {@link FeatureFlags.Module} is what makes the first two the same value by
 * construction rather than by agreement.
 *
 * <p>An administrator's stored value wins; null means "the environment decides", which is what
 * "Use the environment default" writes when they clear the field. Modelled on
 * {@code timetracking.TimeTrackingSettings}: the stored block is held in a volatile field and
 * replaced when {@link SettingsService.SettingsChangedEvent} says it changed, so a switch takes
 * effect on the next request without a restart. Deliberately <em>not</em> modelled on
 * {@code mcp.McpSettings}, which reads Mongo on every call — affordable for a transport gate
 * consulted once per connection, not for a resolver on the path of every request to the module.
 */
@Component
@RequiredArgsConstructor
public class ProjectTemplateSettings implements FeatureFlags.Module {

	/** The client-visible flag name, snake_case like {@code absence_management}. */
	public static final String FLAG = "project_templates";

	private final SettingsService settings;
	private final HinataProperties properties;

	/**
	 * The stored override. Never holds null once loaded — an instance with no
	 * {@code projectTemplates} block caches an empty one, so the absence costs a single read
	 * rather than one per request.
	 */
	private volatile ServerSettings.ProjectTemplates cached;

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		cached = orEmpty(event.settings().getProjectTemplates());
	}

	@Override
	public String flagKey() {
		return FLAG;
	}

	@Override
	public boolean flagEnabled() {
		return enabled();
	}

	/** Whether the module is switched on at all. */
	public boolean enabled() {
		return enabledIn(db());
	}

	/**
	 * The same question against a block that is <em>not</em> the cached one — the document an
	 * event is carrying, before this class has had its turn at it.
	 *
	 * <p>Listeners of {@code SettingsChangedEvent} run in an order nobody declares, so a second
	 * listener that asked {@link #enabled()} would get last save's answer whenever it happened to
	 * run first — on exactly the save that switched the module on, which is the one save where
	 * being wrong is visible.
	 */
	public boolean enabledIn(ServerSettings.ProjectTemplates block) {
		Boolean override = orEmpty(block).getEnabled();
		return override != null ? override : env().isEnabled();
	}

	/** As above, for a whole settings document rather than the one block. */
	public boolean enabledIn(ServerSettings document) {
		return enabledIn(document == null ? null : document.getProjectTemplates());
	}

	private ServerSettings.ProjectTemplates db() {
		ServerSettings.ProjectTemplates current = cached;
		if (current == null) {
			current = orEmpty(settings.get().getProjectTemplates());
			cached = current;
		}
		return current;
	}

	private static ServerSettings.ProjectTemplates orEmpty(ServerSettings.ProjectTemplates stored) {
		return stored != null ? stored : new ServerSettings.ProjectTemplates();
	}

	private HinataProperties.ProjectTemplates env() {
		return properties.getProjectTemplates();
	}
}
