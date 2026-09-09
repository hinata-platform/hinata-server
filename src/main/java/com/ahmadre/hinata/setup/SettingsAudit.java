package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.user.User;

/**
 * A module's chance to say, in its own words, what an administrator just changed
 * about it.
 *
 * <p>Every settings save already records one {@code SETTINGS_CHANGED} event.
 * That is the right grain for "somebody edited the configuration" and the wrong
 * one for a rule with legal weight: a lock date that moved, or a required field
 * that appeared, is a decision people will later need to reconstruct, and a
 * single event that says only "settings changed" cannot tell them which of
 * forty fields it was.
 *
 * <p>The direction is inverted for the same reason {@link SettingsPrefill}'s is:
 * {@code setup} knows the shape of the document but not what a module's fields
 * mean, and the module boundary (see {@code timetracking.ModuleBoundaryTest})
 * forbids it from asking. So the module implements this and is handed both
 * versions.
 *
 * <p>Called <em>before</em> the write, with the stored document and the one
 * about to replace it. Before, because an implementation that recorded
 * afterwards could not record the save that switched auditing off — the same
 * reason {@code SETTINGS_CHANGED} is written first. The cost of that order is
 * stated rather than hidden: a save that then fails leaves a record of a change
 * that did not happen. It is the better of the two failures — the request
 * answers 500, so nobody believes the change landed, whereas a record that is
 * missing because the same save disabled auditing looks exactly like a change
 * nobody made.
 *
 * <p>Implementations must not modify either document, and must not throw: a
 * failure to describe a change is never a reason to refuse it.
 */
public interface SettingsAudit {

	/**
	 * Records what changed in this module's part of the document.
	 *
	 * @param before the settings as stored
	 * @param after  the settings that are about to be stored
	 * @param actor  the administrator making the change
	 */
	void record(ServerSettings before, ServerSettings after, User actor);
}
