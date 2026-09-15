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
 * person recorded themselves count, and only on issues that never changed project.
 */
@Component
@RequiredArgsConstructor
public class TimeAvailabilityPolicy implements AvailabilityPolicy {

	private final TimeTrackingSettings settings;
	private final MongoTemplate mongo;

	@Override
	public boolean leadsSeeMemberAbsences() {
		return settings.advancedEnabled() && settings.leadsSeeMemberEntries();
	}

	@Override
	public Set<String> projectsWorkedOn(String userId, LocalDate since) {
		Set<String> projects = new HashSet<>(mongo.findDistinct(
				Query.query(ownEntries(userId, since).and("issueId").is(null)), "projectId", WorkItem.class,
				String.class));
		List<String> issueIds = mongo.findDistinct(
				Query.query(ownEntries(userId, since).and("issueId").ne(null)), "issueId", WorkItem.class,
				String.class);
		if (!issueIds.isEmpty()) {
			// A move carries every entry on an issue into the project it lands in, whoever booked them,
			// and a member of the old project may move it into one they lead. Former ids are written by
			// a move and nothing else.
			Query neverMoved = Query.query(new Criteria().andOperator(
					Criteria.where("_id").in(issueIds),
					new Criteria().orOperator(
							Criteria.where("formerReadableIds").exists(false),
							Criteria.where("formerReadableIds").size(0))));
			projects.addAll(mongo.findDistinct(neverMoved, "projectId", Issue.class, String.class));
		}
		projects.remove(null);
		return Set.copyOf(projects);
	}

	/**
	 * The person's entries from [since] that they recorded themselves: not a smart commit, which can
	 * name anybody as its author, not a copy of somebody else's shared entry, and not the unattributed
	 * remainder from before 2.0.
	 */
	private static Criteria ownEntries(String userId, LocalDate since) {
		return Criteria.where("userId").is(userId).and("date").gte(since)
				.and("source").nin(WorkItem.Source.SMART_COMMIT, WorkItem.Source.SHARED, WorkItem.Source.LEGACY);
	}
}
