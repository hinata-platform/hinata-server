package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The one answer to "may this user see this issue": whoever reaches its project
 * ({@link ProjectReach}) plus the people the issue is shared with ({@link Issue#getParticipantIds()}).
 *
 * <p>Same shape as {@link ProjectReach} and for the same reason: one rule, asked per user
 * ({@link #canSee}) and in bulk ({@link #whoCanSee}) by the notification fan-out, and
 * {@code IssueReachTest} pins the two answers to each other.
 *
 * <p>Seeing is not editing. A participant reads and comments; field changes stay with whoever
 * reaches the project, which {@link #isParticipantOnly} tells apart.
 */
@Component
@RequiredArgsConstructor
public class IssueReach {

	private final ProjectReach projects;

	/** Whether {@code user} may see {@code issue}: project reach or participation. */
	public boolean canSee(Issue issue, User user) {
		if (issue == null || user == null) {
			return false;
		}
		return isParticipant(issue, user.getId()) || projects.canSee(issue.getProjectId(), user);
	}

	/**
	 * The subset of {@code userIds} that may see {@code issue}: one project read for the
	 * project rule, then the participant list from the issue already in hand.
	 */
	public Set<String> whoCanSee(Issue issue, Collection<String> userIds) {
		if (issue == null || userIds == null || userIds.isEmpty()) {
			return Set.of();
		}
		// Participants first: they are answered from the issue in hand, and leaving them out
		// spares the project read and the team query for a list of only them.
		Set<String> allowed = new HashSet<>();
		List<String> rest = new java.util.ArrayList<>();
		for (String id : userIds) {
			if (id == null) continue;
			if (isParticipant(issue, id)) allowed.add(id);
			else rest.add(id);
		}
		if (!rest.isEmpty()) {
			allowed.addAll(projects.whoCanSee(issue.getProjectId(), rest));
		}
		return allowed;
	}

	/**
	 * Of {@code issues}, all in one project, which each of {@code userIds} may see: one project
	 * question for the whole group, then each issue's participants. The bulk form of
	 * {@link #whoCanSee} for fan-outs over many issues at once.
	 */
	public java.util.Map<String, Set<String>> whoCanSeeEach(String projectId, Collection<Issue> issues,
			Collection<String> userIds) {
		// Somebody shared every one of these issues needs no project answer at all.
		List<String> ask = userIds.stream().filter(java.util.Objects::nonNull)
				.filter(id -> !issues.stream().allMatch(issue -> isParticipant(issue, id))).toList();
		Set<String> members = ask.isEmpty() ? Set.of() : projects.whoCanSee(projectId, ask);
		java.util.Map<String, Set<String>> out = new java.util.HashMap<>();
		for (Issue issue : issues) {
			Set<String> allowed = new HashSet<>(members);
			for (String id : userIds) {
				if (isParticipant(issue, id)) allowed.add(id);
			}
			out.put(issue.getId(), allowed);
		}
		return out;
	}

	/** Whether {@code user} sees {@code issue} only because it is shared with them. */
	public boolean isParticipantOnly(Issue issue, User user) {
		if (issue == null || user == null || !isParticipant(issue, user.getId())) {
			return false;
		}
		return !projects.canSee(issue.getProjectId(), user);
	}

	/**
	 * Whether the issue is shared with {@code userId}. The second half of the rule, for a caller
	 * that has already asked {@link ProjectReach} about a whole group of issues of one project at
	 * once and only needs to add the participants of each.
	 */
	public static boolean isParticipant(Issue issue, String userId) {
		List<String> participants = issue.getParticipantIds();
		return userId != null && participants != null && participants.contains(userId);
	}
}
