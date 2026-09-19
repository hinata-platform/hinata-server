package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who decides a request, worked out once when it is submitted.
 *
 * <p>The rule comes from the type, because that is where an operator sets it: a vacation type may
 * go to the team's leads while unpaid leave goes to the administrators. It is resolved at
 * submission and stored on the request — see {@code TimeOffRequest} for why an inbox that
 * recomputed its audience would lose requests out from under the person deciding them.
 *
 * <p><b>Nothing is ever approved by silence.</b> A person with no team, a team with no lead, an
 * empty keeper list — each of them would leave a request nobody can see. Every one of them falls
 * back to the administrators instead. A request sitting unanswered in a visible inbox is a
 * problem somebody can act on; a request that disappeared is not.
 *
 * <p><b>And nobody decides their own.</b> A lead who asks for leave is, by the rule, among the
 * people the rule would ask — so they are struck from their own audience here, and struck again
 * at the decision. Two guards for one rule, because this is the one an administrator could
 * otherwise walk around by construction (§ 87 Abs. 1 Nr. 5 BetrVG makes the holiday plan a matter
 * for agreement, not for whoever happens to hold the account).
 */
@Component
@RequiredArgsConstructor
public class TimeOffApprovers {

	/** Teams one person's routing will look at, so a very large membership cannot fan out. */
	static final int TEAMS_MAX = 50;

	private final TeamRepository teams;
	private final UserRepository users;
	private final TimeTrackingSettings settings;

	/**
	 * Who may decide a request of [type] made by [person] — never [person] themselves, never
	 * empty.
	 *
	 * <p>{@link TimeOffType.ApproverRule#AUTO} returns nobody on purpose: there is nothing to
	 * route, because the server approves it as it arrives. The caller checks the rule rather than
	 * reading an empty set as an error.
	 */
	public Set<String> of(TimeOffType type, User person) {
		if (type.approverRule() == TimeOffType.ApproverRule.AUTO) {
			return Set.of();
		}
		Set<String> found = switch (type.approverRule()) {
			case TEAM_LEAD -> teamLeadsOf(person);
			case NAMED -> new LinkedHashSet<>(settings.absenceManagers());
			case ADMIN, AUTO -> adminIds();
		};
		found.remove(person.getId());
		if (found.isEmpty()) {
			// Not "approved by nobody" and not an error thrown at the person asking for leave:
			// the administrators are always there and can always decide.
			found = adminIds();
			found.remove(person.getId());
		}
		return found;
	}

	/** The administrators who could still act on it, used as the floor everywhere above. */
	public Set<String> adminIds() {
		return users.findByRolesContainingAndActiveIsTrue(Role.ADMIN).stream()
				.map(User::getId)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	/**
	 * The leads of the teams [person] is on.
	 *
	 * <p>A team's lead is its team administrator — the role that already decides who joins it and
	 * what it may reach. Inventing a second kind of lead for absences would mean two lists to keep
	 * in step, and the day they disagreed somebody would be approving leave for a team they no
	 * longer run.
	 *
	 * <p>Only teams an administrator or an absence keeper created, though: see below.
	 */
	private Set<String> teamLeadsOf(User person) {
		List<Team> membership = teams.findByMembersUserId(person.getId());
		Set<String> leads = new LinkedHashSet<>();
		int seen = 0;
		Set<String> sanctioned = null;
		for (Team team : membership) {
			if (seen++ >= TEAMS_MAX) {
				break;
			}
			// Only a team an administrator or an absence keeper set up counts. Anybody may make a
			// team and add anybody to it without being asked — that is what teams are for — so a
			// team whose creator is neither would let one person become the approver of another
			// person's statutory leave by pressing "new team" and "add member". Where the rule
			// finds no such team, `of` falls back to the administrators.
			if (sanctioned == null) {
				sanctioned = new LinkedHashSet<>(adminIds());
				sanctioned.addAll(settings.absenceManagers());
			}
			if (team.getCreatedBy() == null || !sanctioned.contains(team.getCreatedBy())) {
				continue;
			}
			for (TeamMembership member : team.getMembers()) {
				if (member.isAdmin()) {
					leads.add(member.getUserId());
				}
			}
		}
		return leads;
	}
}
