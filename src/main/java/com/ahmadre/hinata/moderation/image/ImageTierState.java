package com.ahmadre.hinata.moderation.image;

/**
 * Whether images are actually being classified — as opposed to whether the policy
 * says they should be.
 *
 * <p>This type exists because those two questions had the same answer in the UI and
 * different answers in reality. {@code imageEnabled} defaults to {@code true}, the
 * admin panel renders a switch that reads "on", and with no {@link ImageModerator}
 * on the classpath every upload came back {@code DISABLED} — so an operator could
 * read the toggle, conclude their attachments were being checked, and be wrong,
 * with nothing anywhere saying otherwise.
 *
 * <p>A self-hosted install running without an image tier is a perfectly good
 * configuration and stays the default; the bug was never the absence of a
 * classifier, it was the product's inability to tell "nobody switched this on" apart
 * from "this is broken". Four states, because those are four different things an
 * operator would do something different about.
 */
public enum ImageTierState {

	/** The operator turned image classification off. Nothing to report. */
	DISABLED_BY_POLICY,

	/**
	 * Policy wants images classified and no tier is installed. The default for a
	 * fresh install, and the state that used to be indistinguishable from
	 * {@link #ACTIVE} from the outside.
	 */
	NOT_CONFIGURED,

	/**
	 * A tier is installed but currently answering that it cannot work — a sidecar
	 * that is down, or whose model failed to load. Distinct from
	 * {@link #NOT_CONFIGURED} because the operator's next action is different:
	 * one is "install something", the other is "something you installed is broken".
	 */
	CONFIGURED_UNAVAILABLE,

	/** A tier is installed, available, and images are genuinely being classified. */
	ACTIVE;

	/** Whether an operator should be told about this state without being asked. */
	public boolean warrantsAttention() {
		return this == NOT_CONFIGURED || this == CONFIGURED_UNAVAILABLE;
	}
}
