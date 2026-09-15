package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * What a board's filter and its row of faces offer, over every active card of the board's projects its
 * viewer may see.
 *
 * <p>No part is gathered by reading the cards, which would cost as much as the board is large: the
 * people assigned and the labels come off their indexes one value at a time, the reporters by a
 * distinct scan, the states from the projects' workflows, the types and priorities from the shape and
 * the issue model, and the epics off the state index, which holds the type as a key. So the facets are
 * gathered afresh on every request, and a filter never offers what was on the board a while ago.
 */
@Component
@RequiredArgsConstructor
class BoardFacetsReader {

	private static final int MAX_PEOPLE = 200;
	private static final int MAX_LABELS = 500;
	private static final int MAX_EPICS = 200;

	private static final List<String> PRIORITIES = Arrays.stream(Issue.Priority.values()).map(Enum::name).toList();
	private static final Sort EPIC_ORDER = Sort.by(Sort.Order.asc("numberInProject"), Sort.Order.asc("_id"));

	private final BoardIssueReads issues;
	private final BoardCardAssembler assembler;

	/** What the filter offers for the cards of [shape] in [scope]. */
	BoardReader.BoardFacets facets(BoardScope scope, BoardQuery.Shape shape) {
		List<String> projectIds = scope.projectIds();
		List<String> assignees = issues.keysOf("assigneeIds", projectIds, BoardCriteria.BY_ASSIGNEE, MAX_PEOPLE);
		List<String> reporters = issues.distinct("reporterId", projectIds, BoardCriteria.BY_REPORTER).stream()
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.limit(MAX_PEOPLE)
				.toList();
		List<String> labels = issues.keysOf("tags", projectIds, BoardCriteria.BY_LABEL, MAX_LABELS);
		List<String> types = shape.types().stream().map(Enum::name).toList();
		return new BoardReader.BoardFacets(assignees, reporters, labels, scope.workflowStates(), types, PRIORITIES,
				epics(projectIds), assembler.people(Stream.concat(assignees.stream(), reporters.stream())));
	}

	/**
	 * The epics of [projectIds] in the projects' order, the oldest first, so the ceiling keeps the same
	 * ones on every read. The state index holds the type as a key, so the read fetches the epics alone.
	 */
	private List<BoardRef> epics(List<String> projectIds) {
		if (projectIds.isEmpty()) {
			return List.of();
		}
		Criteria epics = Criteria.where("projectId").in(projectIds).and("archived").is(false)
				.and("type").is(Issue.Type.EPIC.name());
		return issues.find(epics, EPIC_ORDER, 0, MAX_EPICS, BoardCriteria.BY_STATE, BoardRef.FIELDS).stream()
				.sorted(Comparator.<Issue>comparingInt(epic -> projectIds.indexOf(epic.getProjectId()))
						.thenComparingLong(Issue::getNumberInProject))
				.map(BoardRef::of)
				.toList();
	}
}
