package com.ahmadre.hinata.moderation;

import java.time.Duration;

/**
 * How fast moderation answers (HIN-170). A case waiting this long reminds its moderators, then it
 * is overdue: the admins hear of it and the pills count it. The published rules and the notices
 * name the same hours.
 */
public final class ResponseTimes {

	/** A case waiting this long reminds whoever may see it. */
	public static final Duration REMIND_AFTER = Duration.ofHours(20);

	/** A case waiting this long is overdue; the rules promise an answer within it. */
	public static final Duration OVERDUE_AFTER = Duration.ofHours(24);

	private ResponseTimes() {
	}

	/** [time] in whole hours as the texts name it, in the digits the bundles write. */
	public static String hours(Duration time) {
		return String.valueOf(time.toHours());
	}
}
