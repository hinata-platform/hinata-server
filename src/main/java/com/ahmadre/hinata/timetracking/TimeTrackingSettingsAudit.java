package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsAudit;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Says which time-tracking policy an administrator just moved, and to what.
 *
 * <p>The lock date gets an event of its own. Everything else in this block is a
 * rule about how the module behaves; the lock date is a statement that a stretch
 * of everybody's recorded working time is now unchangeable, and lifting it is
 * the one way to edit a frozen week. A works council asking six months later who
 * opened January and when has to find one record with one name on it, not a
 * {@code SETTINGS_CHANGED} among forty.
 *
 * <p>Nothing here reads the entries or the people. It compares two configuration
 * documents and writes what differs, values included — the values are an
 * operator's decisions, not anybody's data.
 */
@Component
@RequiredArgsConstructor
public class TimeTrackingSettingsAudit implements SettingsAudit {

	/**
	 * How much of one value ends up in the record. Long enough for a date, a
	 * number or an enum name — which is all these fields hold — and short enough
	 * that the free-text privacy notice is summarised rather than copied into the
	 * audit log twenty thousand characters at a time.
	 */
	private static final int MAX_VALUE = 80;

	private final AuditService audit;

	@Override
	public void record(ServerSettings before, ServerSettings after, User actor) {
		ServerSettings.TimeTracking was = block(before);
		ServerSettings.TimeTracking now = block(after);
		// The controller hands the same object back for a block the request
		// omitted, which means "no opinion" rather than "cleared". Identity is
		// the cheapest way to say nothing was said.
		if (was == now) {
			return;
		}
		recordLockDate(was, now, actor);
		recordPolicies(was, now, actor);
	}

	/** The freeze, on its own, with both dates spelled out. */
	private void recordLockDate(ServerSettings.TimeTracking was, ServerSettings.TimeTracking now,
			User actor) {
		Object lockBefore = was.getLockBefore();
		Object lockAfter = now.getLockBefore();
		if (Objects.equals(lockBefore, lockAfter)) {
			return;
		}
		audit.event(AuditAction.TIME_LOCK_CHANGED).actor(actor)
				.meta("lockBefore", text(lockBefore))
				.meta("lockAfter", text(lockAfter))
				.log();
	}

	/**
	 * Every other policy that moved, as {@code field: old → new}.
	 *
	 * <p>One event for the save rather than one per field: they were changed
	 * together on one screen by one person, and splitting them would make the
	 * feed harder to read without making it say more.
	 */
	private void recordPolicies(ServerSettings.TimeTracking was, ServerSettings.TimeTracking now,
			User actor) {
		Map<String, String> changes = new LinkedHashMap<>();
		compare(changes, "advancedEnabled", was, now, ServerSettings.TimeTracking::getAdvancedEnabled);
		compare(changes, "requiredFields", was, now,
				block -> block.getRequiredFields() == null ? null
						: "project=" + block.getRequiredFields().getProject()
								+ "/issue=" + block.getRequiredFields().getIssue()
								+ "/description=" + block.getRequiredFields().getDescription()
								+ "/tag=" + block.getRequiredFields().getTag());
		compare(changes, "rounding", was, now,
				block -> block.getRounding() == null ? null
						: block.getRounding().getMode() + "/" + block.getRounding().getIncrement());
		compare(changes, "limitTagAccess", was, now, ServerSettings.TimeTracking::getLimitTagAccess);
		compare(changes, "defaultBillable", was, now, ServerSettings.TimeTracking::getDefaultBillable);
		compare(changes, "leadsSeeMemberEntries", was, now,
				ServerSettings.TimeTracking::getLeadsSeeMemberEntries);
		compare(changes, "approvalsEnabled", was, now,
				ServerSettings.TimeTracking::getApprovalsEnabled);
		compare(changes, "approvalPeriod", was, now,
				block -> block.getApprovalPeriod() == null ? null
						: String.valueOf(block.getApprovalPeriod().getType()));
		compare(changes, "workloadReportsEnabled", was, now,
				ServerSettings.TimeTracking::getWorkloadReportsEnabled);
		compare(changes, "alertsEnabled", was, now, ServerSettings.TimeTracking::getAlertsEnabled);
		compare(changes, "targetRemindersEnabled", was, now,
				ServerSettings.TimeTracking::getTargetRemindersEnabled);
		compare(changes, "arbzgHintsEnabled", was, now,
				ServerSettings.TimeTracking::getArbzgHintsEnabled);
		compare(changes, "icsImportEnabled", was, now,
				ServerSettings.TimeTracking::getIcsImportEnabled);
		compare(changes, "billingEnabled", was, now, ServerSettings.TimeTracking::getBillingEnabled);
		compare(changes, "currency", was, now, ServerSettings.TimeTracking::getCurrency);
		compare(changes, "retention", was, now,
				block -> block.getRetention() == null ? null
						: block.getRetention().getDescriptionPurgeMonths()
								+ "/" + block.getRetention().getEntryPurgeMonths());
		// The notice is free text an administrator writes; that it changed is the
		// fact worth recording, not what it now says.
		compare(changes, "privacyNotice", was, now,
				block -> block.getPrivacyNotice() == null || block.getPrivacyNotice().isBlank()
						? null : "set (" + block.getPrivacyNotice().length() + " characters)");
		if (changes.isEmpty()) {
			return;
		}
		AuditService.Entry entry = audit.event(AuditAction.TIME_POLICY_CHANGED).actor(actor);
		changes.forEach(entry::meta);
		entry.log();
	}

	private static void compare(Map<String, String> changes, String field,
			ServerSettings.TimeTracking was, ServerSettings.TimeTracking now,
			Function<ServerSettings.TimeTracking, Object> read) {
		Object before = read.apply(was);
		Object after = read.apply(now);
		if (!Objects.equals(before, after)) {
			changes.put(field, text(before) + " → " + text(after));
		}
	}

	/** An empty block rather than null, so every comparison below reads one way. */
	private static ServerSettings.TimeTracking block(ServerSettings settings) {
		ServerSettings.TimeTracking stored =
				settings == null ? null : settings.getTimeTracking();
		return stored != null ? stored : new ServerSettings.TimeTracking();
	}

	/** {@code "default"} for a null, because that is what a null means in this block. */
	private static String text(Object value) {
		if (value == null) {
			return "default";
		}
		String text = String.valueOf(value);
		return text.length() > MAX_VALUE ? text.substring(0, MAX_VALUE) + "…" : text;
	}
}
