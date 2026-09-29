package com.ahmadre.hinata.template;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsAudit;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Says when somebody turned project templates on or off, and to what.
 *
 * <p>Every settings save already records one {@code SETTINGS_CHANGED} event, which is the right
 * grain for "somebody edited the configuration" and the wrong one here: this switch decides
 * whether whole projects can be copied, and "when did copying become possible on this instance"
 * has to be one findable line rather than a {@code SETTINGS_CHANGED} among forty.
 *
 * <p>Nothing here reads a project or a person. It compares two configuration documents and writes
 * which way one boolean went.
 */
@Component
@RequiredArgsConstructor
public class ProjectTemplateSettingsAudit implements SettingsAudit {

	private final AuditService audit;

	@Override
	public void record(ServerSettings before, ServerSettings after, User actor) {
		ServerSettings.ProjectTemplates was = block(before);
		ServerSettings.ProjectTemplates now = block(after);
		// The controller hands the same object back for a block the request omitted, which
		// means "no opinion" rather than "cleared". Identity is the cheapest way to say
		// nothing was said.
		if (was == now) {
			return;
		}
		boolean enabledMoved = !Objects.equals(was.getEnabled(), now.getEnabled());
		boolean basisMoved = !Objects.equals(was.getDefaultBasis(), now.getDefaultBasis());
		if (!enabledMoved && !basisMoved) {
			return;
		}
		var event = audit.event(AuditAction.PROJECT_TEMPLATES_POLICY_CHANGED).actor(actor);
		if (enabledMoved) {
			event.meta("enabled", text(was.getEnabled()) + " → " + text(now.getEnabled()));
		}
		if (basisMoved) {
			event.meta("defaultBasis", text(was.getDefaultBasis()) + " → " + text(now.getDefaultBasis()));
		}
		event.log();
	}

	/** An empty block rather than null, so the comparison above reads one way. */
	private static ServerSettings.ProjectTemplates block(ServerSettings settings) {
		ServerSettings.ProjectTemplates stored =
				settings == null ? null : settings.getProjectTemplates();
		return stored != null ? stored : new ServerSettings.ProjectTemplates();
	}

	/** {@code "default"} for a null, because that is what a null means in this block. */
	private static String text(Object value) {
		return value == null ? "default" : String.valueOf(value);
	}
}
