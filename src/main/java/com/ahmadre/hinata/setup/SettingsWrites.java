package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What every write of the settings document goes through, whichever route it
 * comes by — the admin area's whole-document save or the organisation's partial
 * one: the modules' guards, their audit hooks, and the lock exceptions that no
 * body may author.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsWrites {

	/** Modules refusing a save that would break their own rules; see {@link SettingsGuard}. */
	private final List<SettingsGuard> moduleGuards;
	/** Modules describing what changed about them; see {@link SettingsAudit}. */
	private final List<SettingsAudit> moduleAudits;

	/**
	 * Refuses the save, by throwing, if a module's rules forbid it. Before the audit
	 * record and before the write, so a refusal leaves no trace of a change that did
	 * not happen.
	 */
	public void check(ServerSettings current, ServerSettings updated) {
		for (SettingsGuard guard : moduleGuards) {
			guard.check(current, updated);
		}
	}

	/**
	 * The modules' own word on what moved. A module that fails to describe its own
	 * settings never fails the save.
	 */
	public void recordModules(ServerSettings current, ServerSettings updated, User actor) {
		for (SettingsAudit moduleAudit : moduleAudits) {
			try {
				moduleAudit.record(current, updated, actor);
			}
			catch (RuntimeException ex) {
				log.warn("Settings audit hook {} failed: {}", moduleAudit.getClass().getSimpleName(), ex.toString());
			}
		}
	}

	/**
	 * Carries the switches for the organisation's audit records (working time,
	 * timesheets, absences) from {@code source} into {@code target}, and leaves every
	 * other switch as {@code target} has it. The admin area's save keeps the
	 * organisation's switches as stored; the organisation's save sets only those.
	 */
	public void carryOrganisationalAuditEvents(ServerSettings target, java.util.Map<String, Boolean> source) {
		if (target.getAudit() == null) {
			target.setAudit(new ServerSettings.Audit());
		}
		java.util.Map<String, Boolean> events = new java.util.LinkedHashMap<>(
				target.getAudit().getEvents() == null ? java.util.Map.of() : target.getAudit().getEvents());
		for (com.ahmadre.hinata.audit.AuditAction action : com.ahmadre.hinata.audit.AuditAction.values()) {
			if (!action.organisational()) continue;
			Boolean value = source == null ? null : source.get(action.name());
			if (value == null) events.remove(action.name()); else events.put(action.name(), value);
		}
		target.getAudit().setEvents(events);
	}

	/** The organisation's audit switches as stored. */
	public java.util.Map<String, Boolean> organisationalAuditEvents(ServerSettings settings) {
		java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
		if (settings.getAudit() == null || settings.getAudit().getEvents() == null) return out;
		settings.getAudit().getEvents().forEach((key, value) -> {
			try {
				if (com.ahmadre.hinata.audit.AuditAction.valueOf(key).organisational()) out.put(key, value);
			}
			catch (IllegalArgumentException retired) {
				// A switch for an action that no longer exists: nobody's to show.
			}
		});
		return out;
	}

	/**
	 * The reopened spans are not editable through a settings write, whatever the
	 * body says. Each carries who opened it, when and why, and is recorded by the
	 * route that mints it; a whole-block write could otherwise author one with a
	 * hand-written author, or delete one, and leave only a generic record behind.
	 */
	public void keepLockExceptions(ServerSettings updated, ServerSettings current) {
		ServerSettings.TimeTracking incoming = updated.getTimeTracking();
		if (incoming == null) {
			return;
		}
		ServerSettings.TimeTracking stored = current.getTimeTracking();
		incoming.setLockExceptions(stored == null ? null : stored.getLockExceptions());
	}
}
