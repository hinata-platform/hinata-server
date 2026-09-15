package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.AvailabilityPolicy;
import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells availability whether leads see their members' absences, exactly when they see their
 * members' entries, and through which projects: the ones the person recorded time on.
 *
 * <p>One switch for both, because they answer the same question for a works council, whether a
 * lead may see what a particular person is doing, and an instance where a lead saw the absences
 * but not the hours, or the reverse, would need two agreements for one decision. With the module
 * off there is nothing to see.
 *
 * <p>The projects have to be ones nobody else could have put the person's time on, because a lead
 * relationship is cheap: anybody can create a project and add anybody to it. So only entries the
 * person recorded themselves count, and only through the project of an issue that never changed
 * project. Time on no issue does not count: an issue moved into a project and deleted there leaves
 * everybody's hours behind on it with no issue at all.
 */
@Component
@RequiredArgsConstructor
public class TimeAvailabilityPolicy implements AvailabilityPolicy {

	/** The writers of an entry that only the person can be: the app, their timer, their own token. */
	private static final List<WorkItem.Source> OWN_SOURCES = List.of(WorkItem.Source.APP, WorkItem.Source.TIMER,
			WorkItem.Source.MCP);

	private final TimeTrackingSettings settings;
	private final MongoTemplate mongo;

	@Override
	public boolean leadsSeeMemberAbsences() {
		return settings.advancedEnabled() && settings.leadsSeeMemberEntries();
	}

	@Override
	public Set<String> projectsWorkedOn(String userId, Set<String> among, LocalDate since) {
		if (among.isEmpty()) {
			return Set.of();
		}
		// Not a smart commit, which can name anybody as its author, nor a copy of somebody else's
		// shared entry, nor whatever a later import writes: a list of who may count, not of who may not.
		// Documents from before 2.0 carry no source and were written in the app. Answered from
		// user_project_date, and only for the projects the caller asks about.
		Query own = Query.query(new Criteria().andOperator(
				Criteria.where("userId").is(userId),
				Criteria.where("projectId").in(among),
				Criteria.where("date").gte(since),
				Criteria.where("issueId").ne(null),
				new Criteria().orOperator(
						Criteria.where("source").in(OWN_SOURCES),
						Criteria.where("source").exists(false))));
		List<String> issueIds = mongo.findDistinct(own, "issueId", WorkItem.class, String.class);
		if (issueIds.isEmpty()) {
			return Set.of();
		}
		// A move carries every entry on an issue into the project it lands in, whoever booked them, and a
		// member of the old project may move it into one they lead. Former ids are written by a move
		// and by nothing else; a deleted issue is not found at all.
		Query neverMoved = Query.query(new Criteria().andOperator(
				Criteria.where("_id").in(issueIds),
				Criteria.where("projectId").in(among),
				new Criteria().orOperator(
						Criteria.where("formerReadableIds").exists(false),
						Criteria.where("formerReadableIds").size(0))));
		Set<String> projects = new HashSet<>(mongo.findDistinct(neverMoved, "projectId", Issue.class, String.class));
		projects.remove(null);
		return Set.copyOf(projects);
	}
}
