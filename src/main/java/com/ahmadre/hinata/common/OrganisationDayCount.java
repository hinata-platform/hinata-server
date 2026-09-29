package com.ahmadre.hinata.common;

/**
 * How the organisation counts days: calendar or working days (HIN-131).
 *
 * <p>The setting lives with the project templates, where it first meant only how relative
 * deadlines count. It now also decides the default notification times, and the notification
 * code must not reach into the templates module, which an administrator can switch off. So
 * the module answers through this, and nothing outside it names the module.
 */
public interface OrganisationDayCount {

	/** The organisation's choice, else the server's. */
	RelativeDate.Basis dayCount();
}
