package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The issue-link graph of a whole project, flattened into drawable edges for the
 * timeline / Gantt views.
 *
 * <p>Two sources feed one edge list: the real {@link IssueLink} documents and the
 * legacy {@code Issue.dependsOnIds} field, which predates issue links and is
 * still written by the API and the MCP tools. A dependency "A depends on B" is
 * exactly "B blocks A", so it is emitted as a synthetic {@code BLOCKS} edge and
 * deduped against any real link that already says the same thing.
 *
 * <p>Edges are always clipped to a scope of issue ids: an edge is only returned
 * when <em>both</em> ends are inside it. A connector to an issue that isn't on
 * the chart would have nowhere to land, and leaking the existence of issues
 * outside the caller's scope is exactly what {@link IssueLinkService} avoids.
 */
@Service
@RequiredArgsConstructor
public class IssueLinkGraphService {

	private final IssueLinkRepository links;
	private final ProjectService projects;
	private final MongoTemplate mongo;

	/**
	 * One connector between two issues, always stated in the link's own direction:
	 * {@code sourceId} is the outward side of the verb (the issue that "blocks"),
	 * {@code targetId} the inward one (the issue that "is blocked by").
	 */
	public record LinkEdge(String id, IssueLinkType type, String sourceId, String targetId) {
	}

	/** Edges between the given issues — the exact set a chart is about to draw. */
	public List<LinkEdge> among(Collection<Issue> scope) {
		Set<String> ids = new LinkedHashSet<>();
		for (Issue issue : scope) ids.add(issue.getId());
		return edges(ids, scope);
	}

	/**
	 * Every edge between the non-archived issues of {@code projectIds}. Used by the
	 * board timeline, which renders issues it already holds and only needs the
	 * graph; the caller must be a member of each project.
	 */
	public List<LinkEdge> forProjects(List<String> projectIds, User user) {
		if (projectIds == null || projectIds.isEmpty()) return List.of();
		List<String> allowed = new ArrayList<>();
		for (String projectId : projectIds) {
			Project project = projects.get(projectId);
			projects.assertMember(project, user);
			allowed.add(project.getId());
		}
		// Straight through MongoTemplate rather than the repository: an id-only
		// projection can't be mapped back onto Issue (primitive constructor
		// parameters would be null), and the whole entity isn't needed here.
		Query query = new Query(
				Criteria.where("projectId").in(allowed).and("archived").ne(true));
		query.fields().include("_id");
		Set<String> ids = new LinkedHashSet<>();
		for (Document document : mongo.find(query, Document.class, "issues")) {
			Object id = document.get("_id");
			if (id != null) ids.add(id.toString());
		}
		// Only ids are loaded, so the legacy dependsOnIds edges can't be derived
		// here — the board folds those in client-side from issues it already has.
		return edges(ids, List.of());
	}

	private List<LinkEdge> edges(Set<String> ids, Collection<Issue> withDependencies) {
		if (ids.isEmpty()) return List.of();
		List<LinkEdge> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		addStoredLinks(ids, out, seen);
		addLegacyDependencies(ids, withDependencies, out, seen);
		return out;
	}

	private void addStoredLinks(Set<String> ids, List<LinkEdge> out, Set<String> seen) {
		for (IssueLink link : links.findBySourceIdInOrTargetIdIn(ids, ids)) {
			boolean bothOnChart = ids.contains(link.getSourceId()) && ids.contains(link.getTargetId());
			if (bothOnChart && seen.add(key(link.getType(), link.getSourceId(), link.getTargetId()))) {
				out.add(new LinkEdge(link.getId(), link.getType(), link.getSourceId(),
						link.getTargetId()));
			}
		}
	}

	private void addLegacyDependencies(Set<String> ids, Collection<Issue> scope,
			List<LinkEdge> out, Set<String> seen) {
		for (Issue issue : scope) {
			List<String> dependsOn = issue.getDependsOnIds();
			if (dependsOn == null) continue;
			for (String blockerId : dependsOn) {
				boolean drawable = blockerId != null && ids.contains(blockerId)
						&& !blockerId.equals(issue.getId())
						&& seen.add(key(IssueLinkType.BLOCKS, blockerId, issue.getId()));
				if (drawable) {
					out.add(new LinkEdge("dep:" + blockerId + ':' + issue.getId(),
							IssueLinkType.BLOCKS, blockerId, issue.getId()));
				}
			}
		}
	}

	private String key(IssueLinkType type, String sourceId, String targetId) {
		return type.name() + '|' + sourceId + '|' + targetId;
	}
}
