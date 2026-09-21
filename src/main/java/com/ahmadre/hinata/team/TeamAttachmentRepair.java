package com.ahmadre.hinata.team;

import com.ahmadre.hinata.migration.MigrationMarkers;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * One-time repair of team attachments nobody was entitled to make (HIN-118).
 *
 * <p>Until HIN-118, anybody who managed a team — which is anybody who created one — could attach
 * any project to it by id, and the attachment made the team's admins members of that project. The
 * check is in {@link TeamService#attachProjects} now; this takes back what the gap let through
 * before it was closed.
 *
 * <p>Deliberately conservative, because detaching a project a team relies on is worse than leaving
 * one extra attachment for an administrator to look at. An attachment is taken back only when the
 * team's own activity shows it was made by somebody who was neither a platform administrator nor a
 * lead of the project, <em>and</em> no current lead of the project is in the team — a lead who is a
 * member could have made or approved it. Everything else stays, and every repair is logged.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class TeamAttachmentRepair implements ApplicationRunner {

	static final String MIGRATION_ID = "hin-118-team-attachment-repair";

	private final MongoTemplate mongo;
	private final TeamRepository teams;
	private final TeamService service;
	private final MigrationMarkers markers;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MIGRATION_ID)) {
			return;
		}
		int repaired = 0;
		List<TeamActivity> attachments = mongo.find(
				Query.query(Criteria.where("verb").is(TeamActivity.Verb.ATTACHED_PROJECT)), TeamActivity.class);
		for (TeamActivity attached : attachments) {
			try {
				repaired += repair(attached);
			}
			catch (RuntimeException ex) {
				log.warn("TeamAttachmentRepair: could not check attachment {} of team {}", attached.getId(),
						attached.getTeamId(), ex);
			}
		}
		if (repaired > 0) {
			log.warn("TeamAttachmentRepair: detached {} project(s) attached to a team by somebody who did not "
					+ "lead them", repaired);
		}
		markers.markDone(MIGRATION_ID, new Document("detached", repaired));
	}

	/** Detaches the project [attached] names if it was not the actor's to attach; the count done. */
	int repair(TeamActivity attached) {
		Team team = teams.findById(attached.getTeamId()).orElse(null);
		if (team == null || team.getProjectIds().isEmpty()) {
			return 0;
		}
		User actor = attached.getActorId() == null ? null : mongo.findById(attached.getActorId(), User.class);
		if (actor != null && actor.isAdmin()) {
			return 0;
		}
		int detached = 0;
		// The activity names the project by its name at the time; only a project of the team that still
		// carries that name is considered, so a rename leaves the attachment alone.
		for (Project project : mongo.find(Query.query(Criteria.where("_id").in(team.getProjectIds())
				.and("name").is(attached.getObjectLabel())), Project.class)) {
			if (actor != null && leads(project, actor.getId())) {
				continue;
			}
			boolean leadInTeam = team.getMembers().stream().map(TeamMembership::getUserId)
					.filter(Objects::nonNull).anyMatch(member -> leads(project, member));
			if (leadInTeam) {
				continue;
			}
			service.detachUnsanctioned(team, project.getId());
			log.warn("TeamAttachmentRepair: detached project {} from team {}", project.getId(), team.getId());
			detached++;
		}
		return detached;
	}

	private static boolean leads(Project project, String userId) {
		return userId.equals(project.getLeadId())
				|| project.getLeadIds() != null && project.getLeadIds().contains(userId);
	}
}
