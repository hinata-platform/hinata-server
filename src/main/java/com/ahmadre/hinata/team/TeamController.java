package com.ahmadre.hinata.team;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.deletion.DeletionService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * REST surface for the Teams feature. Reads require team visibility; every
 * mutation requires manage rights (platform admin or Team-Admin), except a
 * member removing themselves (self-leave). Sits under the authenticated chain —
 * Team-Admin authority is scoped per team and never platform-wide.
 */
@Tag(name = "Teams")
@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

	private final TeamService teamService;
	private final DeletionService deletion;
	private final CurrentUser currentUser;

	// --- Requests ------------------------------------------------------------

	public record CreateTeamRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Size(min = 2, max = 10) String key,
			@Size(max = 4000) String description,
			int colorHue,
			@Size(max = 40) String icon) {
	}

	public record UpdateTeamRequest(
			@Size(max = 120) String name,
			@Size(min = 2, max = 10) String key,
			@Size(max = 4000) String description,
			Integer colorHue,
			@Size(max = 40) String icon) {
	}

	public record AddMembersRequest(
			@NotEmpty List<String> userIds,
			TeamRole role,
			ProjectAccess access) {
	}

	public record UpdateMemberRequest(TeamRole role, ProjectAccess access) {
	}

	public record AttachProjectsRequest(@NotEmpty List<String> projectIds) {
	}

	public record CreateTeamProjectRequest(
			@NotBlank @Size(min = 2, max = 10) String key,
			@NotBlank @Size(max = 120) String name,
			@Size(max = 4000) String description,
			String color,
			String leadId) {
	}

	// --- Reads ---------------------------------------------------------------

	/** Backstop ceiling on the array-shaped list (the visible set is already
	 * membership-scoped, so this only guards a pathological org). */
	private static final int LIST_CAP = 500;

	@GetMapping
	public List<Team> list(@RequestParam(required = false) String q) {
		List<Team> visible = teamService.visibleTo(currentUser.require());
		String needle = q == null ? null : q.trim().toLowerCase();
		return visible.stream()
				.filter(t -> needle == null || needle.isEmpty()
						|| (t.getName() != null && t.getName().toLowerCase().contains(needle)))
				.limit(LIST_CAP)
				.toList();
	}

	@GetMapping("/{id}")
	public Team get(@PathVariable String id) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertVisible(team, user);
		return team;
	}

	@GetMapping("/{id}/activity")
	public Page<TeamActivity> activity(@PathVariable String id,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		User user = currentUser.require();
		teamService.assertVisible(teamService.get(id), user);
		return teamService.activity(id, page, size);
	}

	// --- Team CRUD -----------------------------------------------------------

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Team create(@RequestBody @Valid CreateTeamRequest request) {
		User user = currentUser.require();
		return teamService.create(user, request.name(), request.key(), request.description(),
				request.colorHue(), request.icon());
	}

	@PatchMapping("/{id}")
	public Team update(@PathVariable String id, @RequestBody @Valid UpdateTeamRequest request) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		return teamService.update(team, user, request.name(), request.description(), request.key(),
				request.colorHue(), request.icon());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		teamService.delete(team);
	}

	/** Access the team grants (members, projects, boards, issues) that members
	 * will lose when the team is deleted — drives the confirmation warning. */
	@GetMapping("/{id}/deletion-impact")
	public DeletionService.TeamImpact deletionImpact(@PathVariable String id) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		return deletion.teamImpact(team);
	}

	/**
	 * Deletes the team over SSE, streaming {@code progress} and a terminal
	 * {@code done}. Projects, boards and issues are untouched; members lose the
	 * access this team granted them.
	 */
	@GetMapping(value = "/{id}/delete-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter deleteStream(@PathVariable String id) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		SseEmitter emitter = deletion.newEmitter();
		deletion.deleteTeam(team, LocaleContextHolder.getLocale(), emitter);
		return emitter;
	}

	// --- Membership ----------------------------------------------------------

	@PostMapping("/{id}/members")
	public Team addMembers(@PathVariable String id, @RequestBody @Valid AddMembersRequest request) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		TeamRole role = request.role() != null ? request.role() : TeamRole.MEMBER;
		ProjectAccess access = request.access() != null ? request.access() : ProjectAccess.none();
		return teamService.addMembers(team, user, request.userIds(), role, access);
	}

	@PatchMapping("/{id}/members/{userId}")
	public Team updateMember(@PathVariable String id, @PathVariable String userId,
			@RequestBody @Valid UpdateMemberRequest request) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		return teamService.updateMembership(team, user, userId, request.role(), request.access());
	}

	@DeleteMapping("/{id}/members/{userId}")
	public Team removeMember(@PathVariable String id, @PathVariable String userId) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		// A member may always remove themselves (leave); otherwise manage rights.
		if (!userId.equals(user.getId())) {
			teamService.assertManage(team, user);
		} else {
			teamService.assertVisible(team, user);
		}
		return teamService.removeMember(team, user, userId);
	}

	// --- Projects ------------------------------------------------------------

	@PostMapping("/{id}/projects")
	public Team attachProjects(@PathVariable String id,
			@RequestBody @Valid AttachProjectsRequest request) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		return teamService.attachProjects(team, user, request.projectIds());
	}

	@PostMapping("/{id}/projects/new")
	@ResponseStatus(HttpStatus.CREATED)
	public Project createProject(@PathVariable String id,
			@RequestBody @Valid CreateTeamProjectRequest request) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		return teamService.createTeamProject(team, user, request.key(), request.name(),
				request.description(), request.color(), request.leadId());
	}

	@DeleteMapping("/{id}/projects/{projectId}")
	public Team detachProject(@PathVariable String id, @PathVariable String projectId) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		return teamService.detachProject(team, user, projectId);
	}
}
