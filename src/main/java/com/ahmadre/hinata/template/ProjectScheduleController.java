package com.ahmadre.hinata.template;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.project.Project;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The project's event date: asking what moving it would do, and then moving it.
 *
 * <p>Both routes sit behind {@link ProjectTemplateGate}, so on an instance without the module they
 * do not exist. The event date is deliberately not part of {@code PATCH /api/v1/projects/{id}}: a
 * field on the settings form that rewrote a hundred deadlines on save would be a screen doing
 * something other than what it says. It is written here, after a preview, and audited.
 */
@Tag(name = "Project templates")
@RestController
@RequestMapping("/api/v1/projects/{id}/schedule")
@RequiredArgsConstructor
public class ProjectScheduleController {

	private final ProjectScheduleService schedule;
	private final CurrentUser currentUser;

	/** The date a client proposes. Null clears the event date, which moves nothing. */
	public record ScheduleRequest(LocalDate eventDate) {
	}

	/** What would move, and what would stay. Changes nothing. */
	@PostMapping("/preview")
	public ProjectScheduleService.Preview preview(@PathVariable String id,
			@RequestBody @Valid ScheduleRequest request,
			@RequestParam(required = false) Integer limit) {
		return schedule.preview(id, ProjectScheduleService.checked(request.eventDate()), limit,
				currentUser.require());
	}

	/** Sets the date and writes the deadlines that follow from it. */
	@PostMapping("/apply")
	public ApplyResponse apply(@PathVariable String id, @RequestBody @Valid ScheduleRequest request) {
		ProjectScheduleService.Result result = schedule.apply(id,
				ProjectScheduleService.checked(request.eventDate()), currentUser.require());
		return new ApplyResponse(result.project(), result.deadlinesMoved(), result.leftAlone());
	}

	/**
	 * The project as it now stands, plus the two numbers the toast says out loud: how many
	 * deadlines moved and how many were left where somebody put them.
	 */
	public record ApplyResponse(Project project, int deadlinesMoved, int leftAlone) {
	}
}
