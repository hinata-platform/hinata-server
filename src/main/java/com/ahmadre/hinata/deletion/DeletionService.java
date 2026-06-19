package com.ahmadre.hinata.deletion;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueActivity;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamActivityRepository;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ArrayOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cascading deletion of boards, projects and teams, streamed over Server-Sent
 * Events so the client can show live progress. The destructive work runs on a
 * background thread ({@link Async}); each step is reported through a
 * {@link DeletionStream}, which terminates with a {@code done} summary or a
 * localized {@code error}.
 *
 * <p>Invariants honored:
 * <ul>
 *   <li><b>Board</b> — removes only the board and its sprints; issues are kept
 *       and merely detached ({@code sprintId} cleared) so no reference dangles.</li>
 *   <li><b>Project</b> — boards solely owned by the project are deleted, shared
 *       boards/teams are dereferenced; issues are either deleted (with their
 *       comments, activity, work items and stored attachments) or migrated to a
 *       target project; project articles are removed.</li>
 *   <li><b>Team</b> — only the team and its activity feed are removed; projects,
 *       boards and issues survive, members simply lose the team-granted access
 *       (which is computed live from team membership).</li>
 * </ul>
 *
 * <p>Authorization and pre-flight validation happen on the request thread in the
 * controllers (where the security context and request locale live); this service
 * is handed already-authorized entities plus resolved options.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeletionService {

	/** Generous idle timeout; a large cascade may take a while. */
	private static final long TIMEOUT_MS = 30 * 60 * 1000L;

	private final AgileBoardRepository boards;
	private final SprintRepository sprints;
	private final IssueRepository issues;
	private final ProjectRepository projects;
	private final ProjectService projectService;
	private final TeamRepository teams;
	private final TeamActivityRepository teamActivity;
	private final StorageService storage;
	private final MongoTemplate mongo;
	private final MessageSource messages;

	// ── public types ────────────────────────────────────────────────────────

	public record BoardImpact(String boardName, AgileBoard.Type type, long sprints,
			long affectedIssues) {
	}

	public record ProjectImpact(String projectName, long boards, long sharedBoards, long sprints,
			long issues, long attachments, long articles, long teams,
			List<MigrationTarget> migrationTargets) {
	}

	public record MigrationTarget(String id, String key, String name, String color) {
	}

	public record TeamImpact(String teamName, long members, long projects, long boards,
			long issues) {
	}

	/** Resolved, validated choices for deleting a project that still has issues. */
	public record ProjectDeleteOptions(IssueStrategy strategy, Project target, long issueCount) {
	}

	public enum IssueStrategy { NONE, DELETE, MIGRATE }

	// ── emitter factory ───────────────────────────────────────────────────────

	/** A fresh SSE emitter with an opening comment so proxies flush immediately. */
	public SseEmitter newEmitter() {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
		try {
			emitter.send(SseEmitter.event().comment("connected"));
		}
		catch (IOException ignored) {
			// Client vanished before we even started; the async task will no-op.
		}
		return emitter;
	}

	private DeletionStream stream(SseEmitter emitter, Locale locale) {
		return new DeletionStream(emitter, messages, locale);
	}

	// ── impact (drives the confirmation warnings) ─────────────────────────────

	public BoardImpact boardImpact(AgileBoard board) {
		List<Sprint> boardSprints = sprints.findByBoardIdOrderByStartDateDesc(board.getId());
		long affected = countIssuesInSprints(sprintIds(boardSprints));
		return new BoardImpact(board.getName(), board.getType(), boardSprints.size(), affected);
	}

	public ProjectImpact projectImpact(Project project, User user) {
		String pid = project.getId();
		List<AgileBoard> projectBoards = boards.findByProjectIdsContains(pid);
		long soleBoards = projectBoards.stream().filter(b -> b.getProjectIds().size() <= 1).count();
		long sharedBoards = projectBoards.size() - soleBoards;
		long sprintCount = projectBoards.stream()
				.filter(b -> b.getProjectIds().size() <= 1)
				.mapToLong(b -> sprints.findByBoardIdOrderByStartDateDesc(b.getId()).size())
				.sum();
		long issueCount = issues.countByProjectId(pid);
		long attachments = countAttachments(pid);
		long articleCount = mongo.count(byProjectId(pid), Article.class);
		long teamCount = teams.findByProjectIdsContains(pid).size();
		List<MigrationTarget> targets = projectService.visibleTo(user).stream()
				.filter(p -> !p.getId().equals(pid))
				.map(p -> new MigrationTarget(p.getId(), p.getKey(), p.getName(), p.getColor()))
				.toList();
		return new ProjectImpact(project.getName(), soleBoards, sharedBoards, sprintCount,
				issueCount, attachments, articleCount, teamCount, targets);
	}

	public TeamImpact teamImpact(Team team) {
		List<String> projectIds = team.getProjectIds();
		long boardCount = projectIds.isEmpty() ? 0
				: mongo.count(new Query(Criteria.where("projectIds").in(projectIds)), AgileBoard.class);
		long issueCount = projectIds.isEmpty() ? 0
				: mongo.count(new Query(Criteria.where("projectId").in(projectIds)), Issue.class);
		return new TeamImpact(team.getName(), team.getMembers().size(), projectIds.size(),
				boardCount, issueCount);
	}

	// ── project pre-flight validation (request thread) ────────────────────────

	/**
	 * Validates the caller's choice for a project that still has issues. Returns
	 * the resolved options; throws a localizable {@link ApiException} (surfaced as
	 * a normal HTTP error) if the strategy or migration target is invalid.
	 */
	public ProjectDeleteOptions validateProjectDelete(Project project, User user,
			String strategy, String migrateToProjectId) {
		long issueCount = issues.countByProjectId(project.getId());
		if (issueCount == 0) {
			return new ProjectDeleteOptions(IssueStrategy.NONE, null, 0);
		}
		if (strategy == null || strategy.isBlank()) {
			throw ApiException.badRequest("error.delete.strategyRequired");
		}
		return switch (strategy.toLowerCase(Locale.ROOT)) {
			case "delete" -> new ProjectDeleteOptions(IssueStrategy.DELETE, null, issueCount);
			case "migrate" -> {
				if (migrateToProjectId == null || migrateToProjectId.isBlank()) {
					throw ApiException.badRequest("error.delete.migrateTargetRequired");
				}
				if (migrateToProjectId.equals(project.getId())) {
					throw ApiException.badRequest("error.delete.sameProject");
				}
				Project target = projectService.get(migrateToProjectId); // 404 if missing
				projectService.assertMember(target, user); // caller must reach the target
				if (target.isArchived()) {
					throw ApiException.badRequest("error.delete.targetArchived");
				}
				yield new ProjectDeleteOptions(IssueStrategy.MIGRATE, target, issueCount);
			}
			default -> throw ApiException.badRequest("error.delete.invalidStrategy");
		};
	}

	// ── streaming entry points (async) ────────────────────────────────────────

	@Async
	public void deleteBoard(AgileBoard board, Locale locale, SseEmitter emitter) {
		DeletionStream progress = stream(emitter, locale);
		try {
			progress.done(deleteBoardCascade(board, progress));
		}
		catch (ApiException ex) {
			progress.failed(ex);
		}
		catch (Exception ex) {
			log.error("Board deletion failed for {}", board.getId(), ex);
			progress.failedUnexpected();
		}
	}

	@Async
	public void deleteProject(Project project, ProjectDeleteOptions options, Locale locale,
			SseEmitter emitter) {
		DeletionStream progress = stream(emitter, locale);
		try {
			progress.done(deleteProjectCascade(project, options, progress));
		}
		catch (ApiException ex) {
			progress.failed(ex);
		}
		catch (Exception ex) {
			log.error("Project deletion failed for {}", project.getId(), ex);
			progress.failedUnexpected();
		}
	}

	@Async
	public void deleteTeam(Team team, Locale locale, SseEmitter emitter) {
		DeletionStream progress = stream(emitter, locale);
		try {
			progress.done(deleteTeamCascade(team, progress));
		}
		catch (ApiException ex) {
			progress.failed(ex);
		}
		catch (Exception ex) {
			log.error("Team deletion failed for {}", team.getId(), ex);
			progress.failedUnexpected();
		}
	}

	/** Non-streaming board delete (legacy {@code DELETE} endpoint) — same cascade. */
	public void deleteBoardNow(AgileBoard board) {
		deleteBoardCascade(board, DeletionStream.noop());
	}

	// ── cascades ──────────────────────────────────────────────────────────────

	private Map<String, Object> deleteBoardCascade(AgileBoard board, DeletionStream progress) {
		List<Sprint> boardSprints = sprints.findByBoardIdOrderByStartDateDesc(board.getId());
		List<String> sprintIds = sprintIds(boardSprints);

		// 1. Detach issues — keep them, just clear the dangling sprint reference.
		long detached = countIssuesInSprints(sprintIds);
		if (!sprintIds.isEmpty()) {
			progress.progress("detachingIssues", 0, 1);
			mongo.updateMulti(new Query(Criteria.where("sprintId").in(sprintIds)),
					new Update().set("sprintId", null), Issue.class);
		}
		progress.progress("detachingIssues", 1, 1);

		// 2. Delete the board's sprints.
		progress.progress("deletingSprints", 0, boardSprints.size());
		if (!boardSprints.isEmpty()) {
			sprints.deleteAll(boardSprints);
		}
		progress.progress("deletingSprints", boardSprints.size(), boardSprints.size());

		// 3. Delete the board itself.
		progress.step("deletingBoard");
		boards.deleteById(board.getId());

		return summary(Map.of(
				"sprints", boardSprints.size(),
				"detachedIssues", detached));
	}

	private Map<String, Object> deleteProjectCascade(Project project, ProjectDeleteOptions options,
			DeletionStream progress) {
		String pid = project.getId();
		List<Issue> projectIssues = issues.findByProjectId(pid, Pageable.unpaged()).getContent();

		switch (options.strategy()) {
			case DELETE -> deleteIssues(projectIssues, pid, progress);
			case MIGRATE -> migrateIssues(projectIssues, options.target(), progress);
			case NONE -> { /* no issues to handle */ }
		}

		int boardsDeleted = cleanBoards(pid, progress);
		int teamsDetached = detachTeams(pid, progress);
		long articles = deleteArticles(pid, progress);

		progress.step("deletingProject");
		projects.deleteById(pid);

		return summary(Map.of(
				"issues", projectIssues.size(),
				"strategy", options.strategy().name(),
				"boardsDeleted", boardsDeleted,
				"teamsDetached", teamsDetached,
				"articles", articles));
	}

	private Map<String, Object> deleteTeamCascade(Team team, DeletionStream progress) {
		// Access is derived live from membership, so removing the team revokes it —
		// nothing to rewrite on projects/issues. Report the step for the user.
		progress.step("revokingAccess");
		progress.step("deletingActivity");
		teamActivity.deleteByTeamId(team.getId());
		progress.step("deletingTeam");
		teams.delete(team);
		return summary(Map.of(
				"members", team.getMembers().size(),
				"projects", team.getProjectIds().size()));
	}

	// ── project sub-steps ─────────────────────────────────────────────────────

	private void deleteIssues(List<Issue> projectIssues, String pid, DeletionStream progress) {
		int total = projectIssues.size();
		progress.progress("deletingIssues", 0, total);
		List<String> issueIds = new ArrayList<>(total);
		int done = 0;
		for (Issue issue : projectIssues) {
			deleteStoredAttachments(issue);
			issueIds.add(issue.getId());
			progress.progress("deletingIssues", ++done, total);
		}
		if (!issueIds.isEmpty()) {
			Query byIssue = new Query(Criteria.where("issueId").in(issueIds));
			mongo.remove(byIssue, IssueComment.class);
			mongo.remove(byIssue, IssueActivity.class);
		}
		mongo.remove(byProjectId(pid), WorkItem.class);
		mongo.remove(byProjectId(pid), Issue.class);
	}

	private void migrateIssues(List<Issue> projectIssues, Project target, DeletionStream progress) {
		int total = projectIssues.size();
		progress.progress("migratingIssues", 0, total);
		Set<String> targetStates = new HashSet<>(target.workflowStateNames());
		String fallbackState = target.workflowStateNames().get(0);
		int done = 0;
		for (Issue issue : projectIssues) {
			long number = projectService.nextIssueNumber(target.getId());
			Update update = new Update()
					.set("projectId", target.getId())
					.set("numberInProject", number)
					.set("readableId", target.getKey() + "-" + number)
					// Sprints belong to (soon-to-be-removed) boards of the old project.
					.set("sprintId", null);
			if (!targetStates.contains(issue.getState())) {
				update.set("state", fallbackState);
			}
			mongo.updateFirst(new Query(Criteria.where("_id").is(issue.getId())), update, Issue.class);
			// Keep the denormalized project on the issue's work items in step.
			mongo.updateMulti(new Query(Criteria.where("issueId").is(issue.getId())),
					new Update().set("projectId", target.getId()), WorkItem.class);
			progress.progress("migratingIssues", ++done, total);
		}
	}

	/** Deletes boards solely owned by the project; dereferences shared boards. */
	private int cleanBoards(String pid, DeletionStream progress) {
		List<AgileBoard> projectBoards = boards.findByProjectIdsContains(pid);
		progress.progress("cleaningBoards", 0, projectBoards.size());
		int deleted = 0;
		int done = 0;
		for (AgileBoard board : projectBoards) {
			if (board.getProjectIds().size() <= 1) {
				List<Sprint> boardSprints = sprints.findByBoardIdOrderByStartDateDesc(board.getId());
				if (!boardSprints.isEmpty()) {
					sprints.deleteAll(boardSprints);
				}
				boards.deleteById(board.getId());
				deleted++;
			}
			else {
				board.getProjectIds().remove(pid);
				boards.save(board);
			}
			progress.progress("cleaningBoards", ++done, projectBoards.size());
		}
		return deleted;
	}

	/** Detaches the project from every team that owns it and from member access. */
	private int detachTeams(String pid, DeletionStream progress) {
		List<Team> owners = teams.findByProjectIdsContains(pid);
		progress.progress("detachingTeams", 0, owners.size());
		int done = 0;
		for (Team team : owners) {
			team.getProjectIds().remove(pid);
			for (TeamMembership member : team.getMembers()) {
				ProjectAccess access = member.getAccess();
				if (access != null && access.getScope() == ProjectAccess.Scope.SOME
						&& access.getProjectIds() != null) {
					access.getProjectIds().remove(pid);
				}
			}
			teams.save(team);
			progress.progress("detachingTeams", ++done, owners.size());
		}
		return owners.size();
	}

	private long deleteArticles(String pid, DeletionStream progress) {
		long count = mongo.count(byProjectId(pid), Article.class);
		progress.progress("deletingArticles", 0, 1);
		mongo.remove(byProjectId(pid), Article.class);
		progress.progress("deletingArticles", 1, 1);
		return count;
	}

	private void deleteStoredAttachments(Issue issue) {
		if (issue.getAttachments() == null || !storage.isConfigured()) {
			return;
		}
		for (Issue.Attachment attachment : issue.getAttachments()) {
			if (attachment.getObjectKey() != null) {
				storage.delete(attachment.getObjectKey());
			}
		}
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private static List<String> sprintIds(List<Sprint> boardSprints) {
		return boardSprints.stream().map(Sprint::getId).toList();
	}

	private long countIssuesInSprints(List<String> sprintIds) {
		if (sprintIds.isEmpty()) {
			return 0;
		}
		return mongo.count(new Query(Criteria.where("sprintId").in(sprintIds)), Issue.class);
	}

	private long countAttachments(String pid) {
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("projectId").is(pid)),
				Aggregation.project()
						.and(ArrayOperators.Size.lengthOfArray("attachments")).as("n"),
				Aggregation.group().sum("n").as("total"));
		AggregationResults<org.bson.Document> result =
				mongo.aggregate(aggregation, "issues", org.bson.Document.class);
		org.bson.Document row = result.getUniqueMappedResult();
		return row != null && row.get("total") instanceof Number total ? total.longValue() : 0;
	}

	private static Query byProjectId(String pid) {
		return new Query(Criteria.where("projectId").is(pid));
	}

	private static Map<String, Object> summary(Map<String, Object> values) {
		return new LinkedHashMap<>(values);
	}
}
