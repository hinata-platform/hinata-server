package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether absence management 2.0 exists on this instance.
 *
 * <p>One question, asked in three places that must never disagree: the gate that answers a request
 * ({@link AbsenceManagementGate}), the flag {@code /api/v1/meta} publishes so the app knows whether
 * to offer the module at all, and the services and jobs that HTTP never reaches — MCP tools, the
 * yearly run, the demo seeder. {@link FeatureFlags.Module} is what makes the first two the same
 * value by construction rather than by agreement.
 *
 * <p><b>The switch is its own, and it is nested.</b> An administrator turns absence management on
 * under Admin → Zeiterfassung, separately from the extended time-tracking module — it brings its
 * own routes, its own screens and its own notifications, and an instance that only records project
 * time has no use for them. But it only takes effect while the extended module is on, because
 * every day it counts comes from a working pattern, a holiday calendar and the capacity built on
 * them, and all three are {@code availability}'s, which the extended module gates. A switch
 * reading "on" over a foundation that is off would be a screen telling an untruth, and the app
 * would offer buttons whose routes answer 404.
 *
 * <p>So the flag is {@code advancedEnabled && absenceManagementConfigured}, and the admin screen
 * shows the stored position of the switch while explaining that it needs the extended module. The
 * two readings are {@link TimeTrackingSettings#absenceManagementConfigured()} — what was set — and
 * {@link #enabled()} — what is in force.
 *
 * <p>No cache of its own: {@link TimeTrackingSettings} already holds the {@code timeTracking} block
 * and refreshes it on {@code SettingsChangedEvent}, so this costs a volatile read. A second
 * resolver over the same block would be a second answer waiting to differ from the first.
 */
@Component
@RequiredArgsConstructor
public class TimeOffSettings implements FeatureFlags.Module {

	/** The client-visible flag name, snake_case like {@code advanced_time_tracking}. */
	public static final String FLAG = "absence_management";

	private final TimeTrackingSettings timeTracking;

	@Override
	public String flagKey() {
		return FLAG;
	}

	@Override
	public boolean flagEnabled() {
		return enabled();
	}

	/** Whether the module is usable: switched on, over an extended module that is also on. */
	public boolean enabled() {
		return timeTracking.advancedEnabled() && timeTracking.absenceManagementConfigured();
	}

	/**
	 * The same answer for a settings document that has just been saved, before any cache has been
	 * told about it.
	 *
	 * <p>{@code SettingsChangedEvent} reaches its listeners in an order nobody declares. A listener
	 * that asked {@link #enabled()} would be asking a cache another listener is about to refresh,
	 * and would get the previous answer on exactly the save that turned the module on — which is
	 * the one save where being wrong is visible.
	 */
	public boolean enabledIn(ServerSettings settings) {
		ServerSettings.TimeTracking block = settings == null ? null : settings.getTimeTracking();
		return timeTracking.advancedEnabledIn(block) && timeTracking.absenceManagementConfiguredIn(block);
	}
}
