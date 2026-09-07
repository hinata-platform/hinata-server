package com.ahmadre.hinata.common;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the effective client feature flags are computed.
 *
 * <p>The rule is: start from the env defaults ({@code hinata.app.feature-flags}),
 * then let an admin's stored override win <em>per key</em> — NOT "the DB wins
 * entirely once it holds anything", which would permanently hide any newly
 * shipped default flag the moment an admin had toggled something else. Finally,
 * put the module flags on top: those are owned by their own settings resolver
 * ({@code mcp}, {@code advanced_time_tracking}, later {@code schedule}) and must
 * not be editable a second time as a free-form map entry.
 *
 * <p>This lived in three hand-kept copies before — {@code MetaController#meta()},
 * {@code AdminSettingsController#get()} and the e-mail-reply gate in
 * {@code IssueEmailReplyService} — and the copies had to agree, because {@code
 * /meta} is what tells the app which buttons to show and the gate is what
 * happens when one is pressed. A rule that decides whether a request is answered
 * or refused belongs in one class.
 */
@Component
@RequiredArgsConstructor
public class FeatureFlags {

	/**
	 * A feature whose switch lives in its own settings block rather than in the
	 * free-form flag map, but which the app still reads as a flag. Implemented by
	 * the module's own settings resolver, so the value published under
	 * {@link #effective()} is by construction the same value the module's gate
	 * enforces — the two cannot drift.
	 */
	public interface Module {

		/** The flag name clients see, snake_case (e.g. {@code advanced_time_tracking}). */
		String flagKey();

		/** Whether the module is on, resolved the module's own way (DB override over env). */
		boolean flagEnabled();
	}

	private final SettingsService settings;
	private final HinataProperties properties;
	private final List<Module> modules;

	/**
	 * What {@code /api/v1/meta} publishes and what every server-side gate checks:
	 * env defaults, admin overrides per key, module flags on top.
	 */
	public Map<String, Boolean> effective() {
		Map<String, Boolean> flags = configurable();
		modules.stream()
				// Sorted so the published map does not silently reorder when bean
				// discovery order changes; JSON key order is nobody's contract, but a
				// diff in a snapshot test should mean something changed.
				.sorted(Comparator.comparing(Module::flagKey))
				.forEach(module -> flags.put(module.flagKey(), module.flagEnabled()));
		return flags;
	}

	/**
	 * The subset an administrator edits as a map in the admin area: env defaults
	 * merged with the stored overrides, <em>without</em> the module flags. A module
	 * flag has a dedicated switch in its own section; offering it here as well
	 * would create a second source of truth for the same setting, and the loser of
	 * that race would be whichever screen was saved last.
	 */
	public Map<String, Boolean> configurable() {
		Map<String, Boolean> flags =
				new LinkedHashMap<>(properties.getApp().getFeatureFlags());
		ServerSettings.App app = settings.get().getApp();
		if (app.getFeatureFlags() != null) {
			flags.putAll(app.getFeatureFlags());
		}
		return flags;
	}

	/** Whether one flag is on, by the same rule {@code /meta} answered with. */
	public boolean enabled(String key) {
		return Boolean.TRUE.equals(effective().get(key));
	}
}
