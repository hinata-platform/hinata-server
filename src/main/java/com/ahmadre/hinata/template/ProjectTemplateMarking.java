package com.ahmadre.hinata.template;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectTemplatePolicy;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The module's answer to {@link ProjectTemplatePolicy}: whether a project may be marked as a
 * template, and the record that somebody did.
 *
 * <p>Its own audit event rather than a line inside {@code SETTINGS_CHANGED}, because marking a
 * project changes where it appears for everybody and offers a button that creates whole projects.
 * "When did this become a template, and who said so" is a question with one findable answer.
 */
@Component
@RequiredArgsConstructor
public class ProjectTemplateMarking implements ProjectTemplatePolicy {

	private final ProjectTemplateSettings settings;
	private final AuditService audit;

	@Override
	public boolean offered() {
		return settings.enabled();
	}

	@Override
	public void recordMarked(Project project, User actor) {
		audit.event(AuditAction.PROJECT_TEMPLATE_MARKED).actor(actor)
				.meta("project", project.getKey())
				.meta("template", String.valueOf(project.isTemplate()))
				.log();
	}
}
