package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.user.UserController.DirectoryUser;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What a page of cards refers to: the epics and parents it names, the sub-tasks of its cards counted,
 * and its people, each in one query for the whole page.
 */
@Component
@RequiredArgsConstructor
class BoardCardAssembler {

	private static final int MAX_PEOPLE = 500;

	private final IssueService issueService;
	private final MongoTemplate mongo;

	/** The cards of one read, with what they refer to. */
	record Cards(Map<String, String> epicOf, Map<String, IssueService.SubtaskTally> tallies,
			List<DirectoryUser> users, List<BoardRef> refs) {

		static final Cards NONE = new Cards(Map.of(), Map.of(), List.of(), List.of());

		List<BoardCard> of(List<Issue> issues) {
			return issues.stream().map(issue -> {
				IssueService.SubtaskTally tally = tallies.getOrDefault(issue.getId(), IssueService.SubtaskTally.NONE);
				return BoardCard.of(issue, epicOf.get(issue.getId()), tally.total(), tally.done());
			}).toList();
		}
	}

	/** Epics, parents, sub-task counts and people for [cards]. */
	Cards cards(BoardScope scope, List<Issue> cards) {
		if (cards.isEmpty()) {
			return Cards.NONE;
		}
		Map<String, Issue> related = new LinkedHashMap<>();
		for (Issue parent : refs(scope, unknown(cards.stream().map(Issue::getParentId), related))) {
			related.put(parent.getId(), parent);
		}
		List<String> grandparentIds = unknown(related.values().stream()
				.filter(parent -> parent.getType() != Issue.Type.EPIC)
				.map(Issue::getParentId), related);
		for (Issue grandparent : refs(scope, grandparentIds)) {
			related.put(grandparent.getId(), grandparent);
		}
		Map<String, String> epicOf = new HashMap<>();
		for (Issue card : cards) {
			String epic = epicOf(card, related);
			if (epic != null) {
				epicOf.put(card.getId(), epic);
			}
		}
		Map<String, IssueService.SubtaskTally> tallies = issueService.subtaskTallies(cards, scope.resolvedStates());
		List<DirectoryUser> people = people(cards.stream().flatMap(card -> Stream.concat(
				Stream.of(card.getAssigneeId(), card.getReporterId()),
				card.getAssigneeIds() == null ? Stream.empty() : card.getAssigneeIds().stream())));
		return new Cards(epicOf, tallies, people, related.values().stream().map(BoardRef::of).toList());
	}

	/**
	 * The active people among [ids], read with the fields of the directory and nothing else of their
	 * accounts.
	 */
	List<DirectoryUser> people(Stream<String> ids) {
		List<Object> wanted = ids.filter(Objects::nonNull).filter(id -> !id.isBlank()).distinct().limit(MAX_PEOPLE)
				.map(id -> ObjectId.isValid(id) ? (Object) new ObjectId(id) : id)
				.toList();
		if (wanted.isEmpty()) {
			return List.of();
		}
		Query query = Query.query(Criteria.where("_id").in(wanted)).maxTime(BoardIssueReads.MAX_TIME);
		query.fields().include(DirectoryUser.FIELDS).include("active");
		return mongo.find(query, Document.class, "users").stream()
				.filter(user -> !Boolean.FALSE.equals(user.getBoolean("active")))
				.map(DirectoryUser::from)
				.toList();
	}

	/** The epic [card] rolls up to: its parent, or for a sub-task its grandparent. */
	private static String epicOf(Issue card, Map<String, Issue> related) {
		Issue parent = card.getParentId() == null ? null : related.get(card.getParentId());
		if (parent == null) {
			return null;
		}
		if (parent.getType() == Issue.Type.EPIC) {
			return parent.getId();
		}
		Issue grandparent = parent.getParentId() == null ? null : related.get(parent.getParentId());
		return grandparent != null && grandparent.getType() == Issue.Type.EPIC ? grandparent.getId() : null;
	}

	private static List<String> unknown(Stream<String> ids, Map<String, Issue> known) {
		return ids.filter(Objects::nonNull).filter(id -> !known.containsKey(id)).distinct().toList();
	}

	/** Issues named by id, as far as they belong to the board's projects this viewer may see. */
	private List<Issue> refs(BoardScope scope, Collection<String> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Query query = Query.query(Criteria.where("_id").in(ids).and("projectId").in(scope.projectIds())
				.and("archived").is(false)).maxTime(BoardIssueReads.MAX_TIME);
		query.fields().include(BoardRef.FIELDS).include(Issue.PROJECTION_REQUIRED);
		return mongo.find(query, Issue.class);
	}
}
