package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which entries a report may add up and whose people it may name, for one reader (HIN-93).
 *
 * <p>Two sets, both written into the query and never filtered afterwards (the lesson of HIN-26):
 *
 * <ul>
 * <li><b>Totals</b> ({@link Reach#totals}): the reader's own entries anywhere, and every entry of a
 * project they can see — as sums per project, activity, tag, issue or day, never per person.
 * Everybody who sees an issue already sees that hours were booked on it.</li>
 * <li><b>People</b> ({@link Reach#people}): the reader's own entries; the entries of a project
 * they lead while {@code leadsSeeMemberEntries} is on — the rule the timesheet, the entry history
 * and the approval inbox follow; and the pre-2.0 remainder that belongs to nobody. A report that
 * names somebody, lists entries or exports rows reads this set.</li>
 * </ul>
 *
 * <p>Entries without a project are their owner's alone in both. An administrator reads
 * everything. Leading a project is something anybody can make themselves, so the lead's reach
 * stops at the policy switch an operator decides on — the same switch the transparency panel
 * names.
 */
@Component
@RequiredArgsConstructor
class TimeReportScope {

	/**
	 * Most projects one reader's reach names. Far past what one person is a member of; a reach
	 * beyond it would be cut, so it refuses instead of answering with less than it claims.
	 */
	static final int MAX_PROJECTS = 5_000;

	private final MongoTemplate mongo;
	private final ProjectReach reach;
	private final TimeApprovers approvers;
	private final TimeTrackingSettings policy;

	/** One reader's two sets. */
	record Reach(String viewerId, boolean everything, Set<String> visibleProjects, Set<String> ledProjects) {

		/** The entries the reader may add up. */
		Criteria totals() {
			if (everything) {
				return new Criteria();
			}
			return new Criteria().orOperator(Criteria.where("userId").is(viewerId),
					Criteria.where("projectId").in(visibleProjects));
		}

		/** The entries whose people the reader may see. */
		Criteria people() {
			if (everything) {
				return new Criteria();
			}
			return new Criteria().orOperator(Criteria.where("userId").is(viewerId),
					new Criteria().andOperator(Criteria.where("userId").is(null),
							Criteria.where("projectId").in(visibleProjects)),
					Criteria.where("projectId").in(ledProjects));
		}

		/** Whether the reader may see anybody's entries but their own. */
		boolean seesOthers() {
			return everything || !ledProjects.isEmpty();
		}
	}

	Reach of(User viewer) {
		if (viewer.isAdmin()) {
			return new Reach(viewer.getId(), true, Set.of(), Set.of());
		}
		Set<String> visible = new LinkedHashSet<>(directProjects(viewer));
		visible.addAll(reach.teamGrantedProjectIds(viewer));
		if (visible.size() > MAX_PROJECTS) {
			throw com.ahmadre.hinata.common.ApiException.badRequest("error.time.report.tooBroad");
		}
		Set<String> led = policy.leadsSeeMemberEntries() ? Set.copyOf(approvers.ledProjectIds(viewer)) : Set.of();
		return new Reach(viewer.getId(), false, Set.copyOf(visible), led);
	}

	/**
	 * The projects the reader belongs to or leads, archived ones included — last year's closed
	 * project is exactly what somebody reports on. Ids only, from the membership index.
	 */
	private Collection<String> directProjects(User viewer) {
		Query query = Query.query(new Criteria().orOperator(Criteria.where("memberIds").is(viewer.getId()),
				Criteria.where("leadIds").is(viewer.getId()), Criteria.where("leadId").is(viewer.getId())))
				.limit(MAX_PROJECTS + 1);
		query.fields().include("_id");
		List<Document> found = mongo.query(Project.class).as(Document.class).matching(query).all();
		return found.stream().map(WorkItemDocuments::id).toList();
	}
}
