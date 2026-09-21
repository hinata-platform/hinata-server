package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.AvailabilityPolicy;
import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
		List<String> issueIds = mongo.findDistinct(Query.query(ownEntries(Criteria.where("userId").is(userId),
				among, since)), "issueId", WorkItem.class, String.class);
		if (issueIds.isEmpty()) {
			return Set.of();
		}
		Set<String> projects = new HashSet<>(
				mongo.findDistinct(neverMoved(issueIds, among), "projectId", Issue.class, String.class));
		projects.remove(null);
		return Set.copyOf(projects);
	}

	/**
	 * The same two questions for many people: which issues they booked themselves, grouped so every
	 * issue carries the people who booked it, then which of those issues never moved. Two queries
	 * whatever the number of people; the group is as large as the number of issues, not of entries.
	 */
	@Override
	public Set<String> whoWorkedOn(Collection<String> userIds, Set<String> among, LocalDate since) {
		if (userIds == null || userIds.isEmpty() || among.isEmpty()) {
			return Set.of();
		}
		Map<String, Set<String>> bookersByIssue = new HashMap<>();
		mongo.aggregate(Aggregation.newAggregation(
						Aggregation.match(ownEntries(Criteria.where("userId").in(userIds), among, since)),
						Aggregation.group("issueId").addToSet("userId").as("userIds")),
				WorkItem.class, Document.class)
				.forEach(row -> bookersByIssue.put(String.valueOf(row.get("_id")),
						new HashSet<>(row.getList("userIds", String.class))));
		if (bookersByIssue.isEmpty()) {
			return Set.of();
		}
		Set<String> workers = new HashSet<>();
		Query unmoved = neverMoved(List.copyOf(bookersByIssue.keySet()), among);
		unmoved.fields().include("_id");
		mongo.query(Issue.class).as(Document.class).matching(unmoved).all()
				.forEach(issue -> workers.addAll(
						bookersByIssue.getOrDefault(String.valueOf(issue.get("_id")), Set.of())));
		return Set.copyOf(workers);
	}

	/**
	 * Not a smart commit, which can name anybody as its author, nor a copy of somebody else's shared
	 * entry, nor whatever a later import writes: a list of who may count, not of who may not.
	 * Documents from before 2.0 carry no source and were written in the app. Answered from
	 * user_project_date, and only for the projects the caller asks about.
	 */
	private static Criteria ownEntries(Criteria who, Set<String> among, LocalDate since) {
		return new Criteria().andOperator(
				who,
				Criteria.where("projectId").in(among),
				Criteria.where("date").gte(since),
				Criteria.where("issueId").ne(null),
				new Criteria().orOperator(
						Criteria.where("source").in(OWN_SOURCES),
						Criteria.where("source").exists(false)));
	}

	/**
	 * A move carries every entry on an issue into the project it lands in, whoever booked them, and a
	 * member of the old project may move it into one they lead. Former ids are written by a move and
	 * by nothing else; a deleted issue is not found at all.
	 */
	private static Query neverMoved(List<String> issueIds, Set<String> among) {
		return Query.query(new Criteria().andOperator(
				Criteria.where("_id").in(issueIds),
				Criteria.where("projectId").in(among),
				new Criteria().orOperator(
						Criteria.where("formerReadableIds").exists(false),
						Criteria.where("formerReadableIds").size(0))));
	}
}
