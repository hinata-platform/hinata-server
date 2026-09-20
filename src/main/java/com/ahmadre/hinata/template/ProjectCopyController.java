package com.ahmadre.hinata.template;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.project.Project;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Copying a project, and asking first what that would involve.
 *
 * <p>Behind {@link ProjectTemplateGate}: on an instance without the module these routes do not
 * exist, and a client that asks gets the same 404 as for a dead link with
 * {@code error.feature.disabled} in the body.
 *
 * <p>The scope route exists so the numbers under the switches in the sheet — how many issues, how
 * many of them sub-tasks, how many files and how large — come from the server rather than from a
 * guess in the app. A guess would be wrong exactly when it mattered, which is next to a switch
 * somebody is deciding about.
 */
@Tag(name = "Project templates")
@RestController
@RequestMapping("/api/v1/projects/{id}/copy")
@RequiredArgsConstructor
public class ProjectCopyController {

	private final ProjectCopyService copies;
	private final CurrentUser currentUser;

	/**
	 * What the sheet sends. Everything but the switches is optional: with no name the copy is
	 * called "… (copy)", with no key the server suggests a free one, and with no event date the
	 * copy carries its rules and no deadlines.
	 */
	public record CopyRequest(
			@Size(max = 120) String name,
			@Size(min = 2, max = 10) String key,
			LocalDate eventDate,
			Boolean includeMembers,
			Boolean includeAttachments,
			Boolean includeTimeSettings,
			Boolean includeBoard,
			/** Whether the copy is itself a template. Used by "save this project as a template". */
			Boolean asTemplate) {
	}

	/** What a copy of this project would involve. Changes nothing. */
	@GetMapping
	public ProjectCopyService.Scope scope(@PathVariable String id) {
		return copies.scopeOf(id, currentUser.require());
	}

	/** Makes the copy. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CopyResponse copy(@PathVariable String id, @RequestBody @Valid CopyRequest request) {
		ProjectCopyService.Options options = new ProjectCopyService.Options(
				request.name(),
				request.key(),
				ProjectScheduleService.checked(request.eventDate()),
				// Members and the project's time settings default to on, attachments and the
				// board to off: the first two describe who works on the plan and how its hours
				// are counted, the second two are bulk that a copy usually does not want.
				request.includeMembers() == null || request.includeMembers(),
				Boolean.TRUE.equals(request.includeAttachments()),
				request.includeTimeSettings() == null || request.includeTimeSettings(),
				Boolean.TRUE.equals(request.includeBoard()),
				Boolean.TRUE.equals(request.asTemplate()));
		ProjectCopyService.Result result = copies.copy(id, options, currentUser.require());
		return new CopyResponse(result.project(), result.issuesCopied(), result.subtasksCopied(),
				result.attachmentsCopied(), result.deadlinesSet());
	}

	/** The new project, and what the toast says about it. */
	public record CopyResponse(Project project, int issuesCopied, int subtasksCopied,
			int attachmentsCopied, int deadlinesSet) {
	}
}
