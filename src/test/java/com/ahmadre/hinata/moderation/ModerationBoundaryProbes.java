package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.timetracking.TimeTrackingSettings;

/**
 * A class written to break the moderation boundary (HIN-26), so {@code ModuleBoundaryTest} can
 * show that the rule fails when it should. Never a Spring bean and never called: it exists to be
 * imported as bytecode.
 */
@SuppressWarnings("unused")
public final class ModerationBoundaryProbes {

	private ModerationBoundaryProbes() {
	}

	/** Reads time tracking, which moderation may not. */
	public static class ReadsTimeTracking {
		private TimeTrackingSettings settings;
	}
}
