package com.ahmadre.hinata.people;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds {@link PersonProfile}: one person, as one viewer may see them.
 *
 * <p>Every member of the organisation sees every active member's directory entry already (pickers,
 * mentions, avatars), so the profile opens for any active account and answers 404 for a missing
 * or deactivated one; an administrator also sees deactivated accounts, the way the user
 * administration does. The shared projects are taken from the viewer's own reach
 * ({@link ProjectService#visibleTo}), so a project the viewer cannot open is never named; the
 * shared teams are teams both belong to, and a team is visible to its members.
 */
@Service
@RequiredArgsConstructor
public class PersonProfileService {

	/** Most shared teams and projects a profile lists. */
	static final int SHARED_MAX = 50;

	private final UserRepository users;
	private final ProjectService projects;
	private final MongoTemplate mongo;
	private final SettingsService settings;
	private final ObjectProvider<PersonAbsences> absences;
	private final Clock clock;

	public PersonProfile profile(User viewer, String personId) {
		User person = personId == null ? null : users.findById(personId).orElse(null);
		boolean admin = viewer.isAdmin();
		// Moderators see whom moderation suspended (HIN-172); a deactivated account stays the admins'.
		boolean moderatesIt = viewer.isModerator() && person != null && person.isSuspended();
		if (person == null || (!person.isActive() && !admin && !moderatesIt)) {
			throw ApiException.notFound("person");
		}
		boolean self = viewer.getId().equals(person.getId());
		ZoneId zone = UserZones.of(person, settings.get());
		PersonAbsences.Absence away = absences.stream()
				.map(source -> source.today(viewer, person.getId(), LocalDate.now(clock.withZone(zone))))
				.flatMap(found -> found.stream()).findFirst().orElse(null);
		return new PersonProfile(person.getId(), person.getDisplayName(), person.getUsername(),
				person.getAvatarUrl(), person.getTitle(), person.getPronouns(), roles(person), zone.getId(),
				sharedTeams(viewer, person), sharedProjects(viewer, person, self), away,
				admin ? person.getEmail() : null, person.isActive(), self, admin, suspensionOf(person, viewer));
	}

	private static PersonProfile.SuspensionView suspensionOf(User person, User viewer) {
		if (!person.isSuspended() || !viewer.isModerator() && !viewer.isAdmin()) {
			return null;
		}
		return new PersonProfile.SuspensionView(person.getSuspension().until(),
				viewer.isModerator() ? person.getSuspension().caseId() : null);
	}

	private static List<String> roles(User person) {
		return Stream.of(Role.ADMIN, Role.ORG_ADMIN, Role.MODERATOR, Role.MEMBER)
				.filter(role -> person.getRoles() != null && person.getRoles().contains(role))
				.map(Role::name).toList();
	}

	/** Teams both are members of: one read on the {@code members_user} index. */
	private List<PersonProfile.TeamRef> sharedTeams(User viewer, User person) {
		Query query = Query.query(Criteria.where("members.userId").all(viewer.getId(), person.getId()))
				.with(Sort.by("name")).limit(SHARED_MAX);
		// colorHue: a primitive the entity's constructor cannot be handed null for.
		query.fields().include("name", "key", "avatarUrl", "icon", "colorHue");
		return mongo.find(query, Team.class).stream()
				.map(team -> new PersonProfile.TeamRef(team.getId(), team.getName(), team.getKey(),
						team.getAvatarUrl(), team.getIcon()))
				.toList();
	}

	private static boolean contains(List<String> ids, String id) {
		return ids != null && ids.contains(id);
	}

	/** The viewer's projects the person is a member of; the viewer's own for their own profile. */
	private List<PersonProfile.ProjectRef> sharedProjects(User viewer, User person, boolean self) {
		return projects.visibleTo(viewer).stream()
				.filter(project -> self || contains(project.getMemberIds(), person.getId())
						|| contains(project.getLeadIds(), person.getId()))
				.sorted(Comparator.comparing(Project::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
				.limit(SHARED_MAX)
				.map(project -> new PersonProfile.ProjectRef(project.getId(), project.getName(), project.getKey(),
						project.getColor()))
				.toList();
	}
}
