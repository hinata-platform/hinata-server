package com.ahmadre.hinata.setup;

/**
 * A module's chance to add its own derived, read-only values to the settings
 * document the admin area reads.
 *
 * <p>{@code setup} knows the shape of {@code server_settings} but not what any
 * one module's values <em>mean</em> — which environment variable stands behind
 * a null, which of two answers wins. That knowledge lives in the module's own
 * resolver, and the module boundary (see {@code timetracking.ModuleBoundaryTest})
 * says {@code setup} may not reach for it. So the direction is inverted: the
 * module implements this and is called, exactly as it implements
 * {@code common.FeatureFlags.Module} to publish its flag.
 *
 * <p>Implementations fill <em>derived</em> fields only — the ones marked
 * {@code @Transient} and {@code READ_ONLY}. Writing into a stored field here
 * would turn a value the operator configured in the environment into a database
 * override the moment an admin opened the page, which is the one thing this
 * indirection exists to prevent.
 */
public interface SettingsPrefill {

	/**
	 * Adds derived values to {@code settings}, in place.
	 *
	 * @param settings the document about to be sent to an administrator
	 */
	void prefill(ServerSettings settings);
}
