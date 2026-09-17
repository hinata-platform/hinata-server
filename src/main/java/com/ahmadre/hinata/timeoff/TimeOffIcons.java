package com.ahmadre.hinata.timeoff;

import java.util.Set;

/**
 * The icon names an absence type may carry.
 *
 * <p>toggl lets an operator pick an emoji. hinata draws Lucide icons and nothing else, so the
 * equivalent is a name from the set the app can actually render — a stored {@code palm-tree} that
 * no icon font knows would leave a hole in a list nobody can fix from the admin screen.
 *
 * <p>An allow-list rather than free text for a second reason: the value is chosen by an
 * administrator and rendered by every client, so it is exactly the kind of field that should not
 * be able to carry anything but a name from a list.
 *
 * <p>Deliberately short. It covers the kinds of absence an organisation actually keeps, and it is
 * an operator's decision to want a twelfth — one line here, and the picker offers it.
 */
final class TimeOffIcons {

	/** What a type falls back to: the same neutral mark the calendar already uses for an absence. */
	static final String DEFAULT = "calendar-off";

	private static final Set<String> ALLOWED = Set.of(
			DEFAULT,
			"palmtree",        // vacation
			"thermometer",     // sickness
			"baby",            // parental leave
			"graduation-cap",  // training
			"heart-pulse",     // care leave
			"scale",           // time off in lieu
			"plane",           // travel
			"home",            // remote or moving day
			"gavel",           // public duty, court, jury service
			"church",          // religious holiday
			"users",           // works council, volunteering
			"clock",           // generic
			"ban");            // unpaid

	private TimeOffIcons() {
	}

	static boolean isAllowed(String icon) {
		return icon != null && ALLOWED.contains(icon);
	}

	/** The icon to store: the one given while it is known, and the neutral default otherwise. */
	static String orDefault(String icon) {
		return isAllowed(icon) ? icon : DEFAULT;
	}

	static Set<String> allowed() {
		return ALLOWED;
	}
}
