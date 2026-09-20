package com.ahmadre.hinata.template;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsPrefill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tells the admin area what the project-template switch currently resolves to, without touching
 * what is stored.
 *
 * <p>The distinction is the whole job. {@code settings.projectTemplates.enabled} is what an
 * administrator decided — null on a fresh instance, and null means "whatever this deployment's
 * environment says". {@code settings.projectTemplates.effective.enabled} is what that null works
 * out to. The admin area shows the second and edits the first, so the switch can be handed back
 * to the environment and stay there.
 *
 * <p>The value comes from {@link ProjectTemplateSettings} rather than from the environment
 * properties directly, so the position on the screen is the one the gate enforces.
 */
@Component
@RequiredArgsConstructor
public class ProjectTemplateAdminPrefill implements SettingsPrefill {

	private final ProjectTemplateSettings settings;

	@Override
	public void prefill(ServerSettings document) {
		ServerSettings.ProjectTemplates stored = document.getProjectTemplates();
		if (stored == null) {
			// So the admin area always has a block to write an override into, even on an
			// instance that has never saved one. Empty, not filled: the field stays null and
			// keeps deferring to the environment.
			stored = new ServerSettings.ProjectTemplates();
			document.setProjectTemplates(stored);
		}
		ServerSettings.ProjectTemplates view = new ServerSettings.ProjectTemplates();
		view.setEnabled(settings.enabled());
		stored.setEffective(view);
	}
}
