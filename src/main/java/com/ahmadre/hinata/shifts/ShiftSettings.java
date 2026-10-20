package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Whether shift planning exists on this instance.
 *
 * <p>One question, asked in places that must never disagree: the gate in front of the routes
 * ({@link ShiftPlanningGate}), the flag {@code /api/v1/meta} publishes so the app knows whether to
 * show the navigation entry, and the services HTTP never reaches (jobs, MCP tools, the demo
 * seeder). {@link FeatureFlags.Module} makes the first two the same value by construction.
 *
 * <p>An administrator's stored value wins; null means "the environment decides"
 * ({@code hinata.shifts.enabled}). Over both stands the deployment: without Mongo transactions
 * the module is off whatever was chosen, because assigning a shift is a transaction and a module
 * that quietly ran without one would hand the last place to two people.
 *
 * <p>Modelled on {@code template.ProjectTemplateSettings}: the stored block is cached in a
 * volatile field and replaced when {@link SettingsService.SettingsChangedEvent} says it changed,
 * so a switch takes effect on the next request without a restart and costs a volatile read.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShiftSettings implements FeatureFlags.Module {

	/** The client-visible flag name, snake_case like {@code project_templates}. */
	public static final String FLAG = "shift_planning";

	/** What a client sees for every module route while the module is off. */
	public static final String DISABLED_KEY = "error.feature.disabled";

	private final SettingsService settings;
	private final HinataProperties properties;

	/**
	 * The stored override. Never holds null once loaded: an instance with no {@code shifts} block
	 * caches an empty one, so the absence costs a single read rather than one per request.
	 */
	private volatile ServerSettings.Shifts cached;

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		ServerSettings.Shifts block = orEmpty(event.settings().getShifts());
		cached = block;
		if (!available() && configuredIn(block)) {
			warnUnavailable("an administrator switched it on");
		}
	}

	/**
	 * Says once at start why a module somebody asked for is not there. Silent when nobody asked:
	 * an instance that never wanted shift planning has nothing to be warned about.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void warnAtStartIfUnavailable() {
		if (!available() && configured()) {
			warnUnavailable("it is switched on");
		}
	}

	@Override
	public String flagKey() {
		return FLAG;
	}

	@Override
	public boolean flagEnabled() {
		return enabled();
	}

	@Override
	public boolean flagAvailable() {
		return available();
	}

	/** Whether the module is in force: chosen by the administrator or the environment, and runnable here. */
	public boolean enabled() {
		return available() && configured();
	}

	/** For every service entry and every job: the same refusal the gate gives over HTTP. */
	public void requireEnabled() {
		if (!enabled()) {
			throw new ApiException(HttpStatus.NOT_FOUND, DISABLED_KEY);
		}
	}

	/**
	 * Whether this deployment could run the module: Mongo transactions are on, which needs a
	 * replica set. A property of the deployment, so it changes only with a restart.
	 */
	public boolean available() {
		return properties.getMongodb().isTransactionsEnabled();
	}

	/**
	 * The switch as it was left, without asking whether the deployment can run it. The admin area
	 * shows this, so turning on transactions later does not appear to flip a switch nobody touched.
	 */
	public boolean configured() {
		return configuredIn(db());
	}

	/**
	 * The same question against a block that is <em>not</em> the cached one, the document an event
	 * is carrying. Listeners of {@code SettingsChangedEvent} run in no declared order, so a second
	 * listener asking {@link #configured()} could get the previous save's answer.
	 */
	public boolean configuredIn(ServerSettings.Shifts block) {
		Boolean override = orEmpty(block).getEnabled();
		return override != null ? override : properties.getShifts().isEnabled();
	}

	private void warnUnavailable(String why) {
		log.warn("Shift planning stays off although {}: it needs MongoDB transactions, which need a "
				+ "replica set. Set hinata.mongodb.transactions-enabled=true (HINATA_MONGO_TRANSACTIONS) "
				+ "on a replica set to use it.", why);
	}

	private ServerSettings.Shifts db() {
		ServerSettings.Shifts current = cached;
		if (current == null) {
			current = orEmpty(settings.get().getShifts());
			cached = current;
		}
		return current;
	}

	private static ServerSettings.Shifts orEmpty(ServerSettings.Shifts stored) {
		return stored != null ? stored : new ServerSettings.Shifts();
	}
}
