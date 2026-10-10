package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether billing exists on this instance, and in which currency.
 *
 * <p>Nested like absence management: the switch is {@code billingEnabled} in the time-tracking
 * block, and it takes effect only while the extended module is on, because every figure billing
 * computes is made of entries the extended module owns. The gate, the flag {@code /meta}
 * publishes and the services that HTTP never reaches all ask {@link #enabled()}, so they cannot
 * disagree.
 *
 * <p>No cache of its own: {@link TimeTrackingSettings} holds the block and refreshes it.
 */
@Component
@RequiredArgsConstructor
public class BillingSettings implements FeatureFlags.Module {

	/** The client-visible flag name. */
	public static final String FLAG = "billing";

	private final TimeTrackingSettings timeTracking;
	private final HinataProperties properties;
	private final SettingsService settings;

	@Override
	public String flagKey() {
		return FLAG;
	}

	@Override
	public boolean flagEnabled() {
		return enabled();
	}

	/** Whether rates, costs and invoices are usable: switched on, over an extended module that is on. */
	public boolean enabled() {
		return timeTracking.advancedEnabled() && timeTracking.billingEnabled();
	}

	/** The one currency every amount on this instance is in (ISO 4217). */
	public String currency() {
		return timeTracking.currency();
	}

	/** How minutes are folded before they are valued — the reports' rounding (stage 6). */
	public TimeTrackingSettings.Rounding rounding() {
		return timeTracking.rounding();
	}

	/** What invoice numbers start with. */
	public String invoicePrefix() {
		return properties.getTimeTracking().getInvoicePrefix();
	}

	/** The instance's zone: the year an invoice number counts in is the issuer's year, not UTC's. */
	public java.time.ZoneId zone() {
		try {
			return java.time.ZoneId.of(settings.get().getGeneral().getTimezone());
		}
		catch (RuntimeException unusable) {
			return java.time.ZoneOffset.UTC;
		}
	}

	/** The organisation's name, for the issuer line of an invoice. */
	public String organization() {
		try {
			String name = settings.get().getOrganizationName();
			return name == null ? "" : name.trim();
		}
		catch (RuntimeException unusable) {
			return "";
		}
	}

	/** Whether a lead sees which of their members booked what. */
	public boolean leadsSeeMemberEntries() {
		return timeTracking.leadsSeeMemberEntries();
	}
}
