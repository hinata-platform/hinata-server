package com.ahmadre.hinata.setup;

/**
 * A module's chance to refuse a settings save that would break its own rules.
 *
 * <p>Bean Validation covers what a field may <em>contain</em>. It cannot cover
 * what a field may contain <em>today</em> — and exactly one setting in the product
 * is like that: the time-tracking lock date, which may never be in the future.
 * A {@code @PastOrPresent} would express the rule but not the answer: it arrives
 * as a field error under the generic "validation failed" sentence, and this is a
 * rule an operator has to be told in words, because the consequence of getting it
 * wrong is that the instance stops accepting the recording of working time that
 * is happening right now (§ 16 Abs. 2 ArbZG, EuGH C-55/18).
 *
 * <p>The direction is inverted for the same reason {@link SettingsPrefill}'s and
 * {@link SettingsAudit}'s are: {@code setup} knows the shape of the document and
 * not what a module's fields mean, and the module boundary (see
 * {@code timetracking.ModuleBoundaryTest}) forbids it from asking. So the module
 * implements this and is handed the document.
 *
 * <p>Unlike {@link SettingsAudit}, an implementation here <em>may</em> throw, and
 * throwing is the whole point: it runs before anything is written and before
 * anything is recorded, so a refusal leaves no trace of a change that did not
 * happen. Implementations must not modify the document.
 */
public interface SettingsGuard {

	/**
	 * Refuses the save, by throwing, if this module's part of it is not allowed.
	 *
	 * @param before the settings as stored
	 * @param after  the settings that would be stored
	 */
	void check(ServerSettings before, ServerSettings after);
}
