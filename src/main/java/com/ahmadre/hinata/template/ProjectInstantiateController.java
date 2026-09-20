package com.ahmadre.hinata.template;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.project.Project;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * "Create a project from this template": a name, a date, and a finished timeline.
 *
 * <p>The same copy underneath, with the scope decided rather than offered. Structure and issues,
 * members and the project's time settings come along; attachments and the board do not. The one
 * decision this route removes is the one nobody wants to make while creating the autumn edition of
 * an event they run every term — and whoever does want to make it uses the copy route instead,
 * which is the same call with the switches exposed.
 *
 * <p>The copy is never itself a template. Instantiating is how a template becomes a project, and a
 * copy that stayed a template would leave somebody with two templates and no project.
 */
@Tag(name = "Project templates")
@RestController
@RequestMapping("/api/v1/projects/{id}/instantiate")
@RequiredArgsConstructor
public class ProjectInstantiateController {

	private final ProjectCopyService copies;
	private final AuditService audit;
	private final CurrentUser currentUser;

	/**
	 * What the sheet sends. The name is required — a project called "Event template (copy)" is
	 * nobody's project — and the date is optional, because a template may be instantiated before
	 * the date is known and moved later with a preview.
	 */
	public record InstantiateRequest(
			@NotBlank @Size(max = 120) String name,
			@Size(min = 2, max = 10) String key,
			LocalDate eventDate) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public InstantiateResponse instantiate(@PathVariable String id,
			@RequestBody @Valid InstantiateRequest request) {
		ProjectCopyService.Options options = new ProjectCopyService.Options(
				request.name(),
				request.key(),
				// Bounded in the service, which is what every protocol goes through.
				request.eventDate(),
				true, false, true, false, false);
		ProjectCopyService.Result result = copies.copy(id, options, currentUser.require());
		// Its own record beside PROJECT_COPIED, which the copy already wrote: "where did our
		// twelve event projects come from" is a different question from "who copied this one".
		audit.event(AuditAction.PROJECT_INSTANTIATED).actor(currentUser.require())
				.meta("template", id)
				.meta("project", result.project().getKey())
				.meta("issues", String.valueOf(result.issuesCopied()))
				.meta("deadlines", String.valueOf(result.deadlinesSet()))
				.meta("eventDate", request.eventDate() == null
						? "none" : request.eventDate().toString())
				.log();
		return new InstantiateResponse(result.project(), result.issuesCopied(),
				result.deadlinesSet());
	}

	/** The new project, and the two numbers the toast says out loud. */
	public record InstantiateResponse(Project project, int issuesCopied, int deadlinesSet) {
	}
}
