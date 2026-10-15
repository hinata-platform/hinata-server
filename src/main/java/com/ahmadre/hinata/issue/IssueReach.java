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
		Set<String> allowed = new HashSet<>(projects.whoCanSee(issue.getProjectId(), userIds));
		for (String id : userIds) {
			if (id != null && isParticipant(issue, id)) {
				allowed.add(id);
			}
		}
		return allowed;
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
