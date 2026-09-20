package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.template.ProjectCopyService;
import com.ahmadre.hinata.template.ProjectTemplateSettings;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Locale;

/**
 * MCP tools for project templates: copying a project, and keeping a deadline as an offset from the
 * project's event date.
 *
 * <p>One of the two named bridges into {@code template} — the module is otherwise closed, and this
 * class exists so that a second protocol reaches the same service the REST route reaches rather
 * than reimplementing its rules. Everything it can refuse, the service refuses: the flag, the
 * membership, the issue limit, the file budget.
 *
 * <p>An instance with the module switched off answers both tools with the same 404
 * {@code error.feature.disabled} its routes answer. A connected client keeps seeing the names in
 * its tool list until it reconnects — tool lists are fetched at connection time — so refusing
 * clearly beats refusing quietly.
 */
@Service
@RequiredArgsConstructor
public class ProjectTemplateTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final ProjectCopyService copies;
	private final ProjectTemplateSettings settings;
	private final ProjectService projectService;
	private final ProjectRepository projects;
	private final IssueService issues;

	@McpTool(name = "copy_project", title = "Copy a project",
			annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false,
					destructiveHint = false, openWorldHint = false),
			description = "Copy a project, with its workflow, labels and every issue including "
					+ "sub-tasks, dependencies and links inside the project. Comments, recorded "
					+ "time, states, sprints and the Git connection are never copied. Give an "
					+ "event date and every deadline kept as an offset is recomputed from it. "
					+ "Requires the project_templates feature.")
	public CopyView copyProject(
			@McpToolParam(description = "Source project id or key (e.g. ASTA)") String idOrKey,
			@McpToolParam(description = "Name of the new project") String name,
			@McpToolParam(description = "Key of the new project, 2-10 letters and digits; "
					+ "omit to let the server suggest one", required = false) String key,
			@McpToolParam(description = "Event date of the new project as yyyy-MM-dd; omit to "
					+ "carry the offsets without resolving them", required = false) String eventDate,
			@McpToolParam(description = "Copy the members and leads (default true)",
					required = false) Boolean includeMembers,
			@McpToolParam(description = "Copy the attachments (default false)",
					required = false) Boolean includeAttachments,
			@McpToolParam(description = "Copy the project's time settings (default true)",
					required = false) Boolean includeTimeSettings,
			@McpToolParam(description = "Copy the project's own board (default false)",
					required = false) Boolean includeBoard,
			@McpToolParam(description = "Mark the copy as a template (default false)",
					required = false) Boolean asTemplate) {
		scopeGuard.require(Scopes.PROJECTS_WRITE);
		User user = currentUser.require();
		Project source = accessibleProject(idOrKey, user);
		ProjectCopyService.Options options = new ProjectCopyService.Options(
				name, key, parseDate(eventDate, "eventDate"),
				includeMembers == null || includeMembers,
				Boolean.TRUE.equals(includeAttachments),
				includeTimeSettings == null || includeTimeSettings,
				Boolean.TRUE.equals(includeBoard),
				Boolean.TRUE.equals(asTemplate));
		ProjectCopyService.Result result = copies.copy(source.getId(), options, user);
		Project copy = result.project();
		return new CopyView(copy.getId(), copy.getKey(), copy.getName(), copy.isTemplate(),
				copy.getEventDate(), result.issuesCopied(), result.subtasksCopied(),
				result.deadlinesSet());
	}

	/** The project a copy produced, and what came along with it. */
	public record CopyView(String id, String key, String name, boolean template,
			LocalDate eventDate, int issuesCopied, int subtasksCopied, int deadlinesSet) {
	}

	@McpTool(name = "set_issue_deadline", title = "Set an issue deadline",
			annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = true,
					destructiveHint = false, openWorldHint = false),
			description = "Keep an issue's deadline as an offset from its project's event date, "
					+ "e.g. 4 weeks before. Negative amounts are before the event, positive ones "
					+ "after. The resulting date is written as usual, so everything that reads a "
					+ "due date goes on reading one. Requires the project_templates feature.")
	public DeadlineView setIssueDeadline(
			@McpToolParam(description = "Issue id or readable id (e.g. ASTA-42)") String idOrReadableId,
			@McpToolParam(description = "Which deadline: due (default) or start",
					required = false) String field,
			@McpToolParam(description = "Distance from the event date; negative is before, "
					+ "positive after. Omit to remove the offset and keep the date as it is.",
					required = false) Integer amount,
			@McpToolParam(description = "DAYS (default) or WEEKS", required = false) String unit,
			@McpToolParam(description = "CALENDAR (default) counts every day; WORKING skips "
					+ "weekends and the holidays of the project's calendar",
					required = false) String basis) {
		scopeGuard.require(Scopes.ISSUES_WRITE);
		User user = currentUser.require();
		if (!settings.enabled()) {
			// The same refusal the HTTP routes give, for the same reason: with the module off
			// this is not a permission problem, the thing simply is not here.
			throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
					"error.feature.disabled");
		}
		boolean start = "start".equalsIgnoreCase(field);
		RelativeDate offset = amount == null ? null : new RelativeDate(amount,
				parseEnum(unit, RelativeDate.Unit.class, RelativeDate.Unit.DAYS, "unit"),
				parseEnum(basis, RelativeDate.Basis.class, RelativeDate.Basis.CALENDAR, "basis"));
		if (offset != null && !offset.withinLimits()) {
			throw ApiException.badRequest("error.issue.offsetOutOfRange");
		}
		Issue resolved = issues.getForUser(idOrReadableId, user);
		Issue saved = issues.update(resolved.getId(), issue -> {
			if (start) {
				issue.setStartOffset(offset);
			}
			else {
				issue.setDueOffset(offset);
			}
		}, user);
		return new DeadlineView(saved.getId(), saved.getReadableId(),
				start ? "start" : "due",
				start ? saved.getStartOffset() : saved.getDueOffset(),
				start ? saved.getStartDate() : saved.getDueDate());
	}

	/** What an issue's deadline now is: the rule, and the date it works out to. */
	public record DeadlineView(String id, String readableId, String field, RelativeDate offset,
			LocalDate date) {
	}

	private Project accessibleProject(String idOrKey, User user) {
		Project project = projects.findById(idOrKey)
				.or(() -> projects.findByKeyIgnoreCase(idOrKey))
				.orElseThrow(() -> ApiException.notFound("project"));
		projectService.assertMember(project, user);
		return project;
	}

	/** An ISO date, or null. A client that sends something else is told which field was wrong. */
	private static LocalDate parseDate(String value, String field) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value.trim());
		}
		catch (RuntimeException notADate) {
			throw ApiException.badRequest("error.mcp.badValue", field);
		}
	}

	private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, E fallback,
			String field) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			throw ApiException.badRequest("error.mcp.badValue", field);
		}
	}
}
