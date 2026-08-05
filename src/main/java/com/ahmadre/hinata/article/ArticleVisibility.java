package com.ahmadre.hinata.article;

import org.springframework.data.mongodb.core.query.Criteria;

import java.util.Set;

/**
 * The one definition of who may see an article, in the two forms the codebase
 * actually needs it: a predicate for documents already in hand, and a
 * {@link Criteria} for documents still in Mongo.
 *
 * <p>Both live here together on purpose. The rule was previously written out
 * inside {@link ArticleController} only, and global search — which reads the same
 * collection — simply did not apply it, so every knowledge article in the
 * organisation was listed to any signed-in account regardless of project or team.
 * A rule that exists in one caller's private method is a rule the next caller does
 * not know about; the fix is not to copy it, it is to have one of it.
 *
 * <p>The query form matters as much as the predicate. Filtering after the fact
 * would be correct and useless: search fetches a bounded candidate window, so
 * dropping the forbidden rows afterwards returns a short page — and, for someone
 * whose own articles all rank below other people's, an empty one. Access has to
 * travel into the query so the window is drawn from what the viewer may see.
 */
public final class ArticleVisibility {

	private ArticleVisibility() {
	}

	/**
	 * Whether [article] is visible to a viewer who can reach [projectIds] and
	 * belongs to [teamIds].
	 *
	 * <p>An article scoped to a project or a team is visible only through that
	 * scope. One with neither is a global note and is visible to every
	 * authenticated user — which is a real product decision, not an oversight: the
	 * knowledge base has an org-wide space, and articles filed there are meant to be
	 * org-wide.
	 */
	public static boolean canSee(Article article, Set<String> projectIds, Set<String> teamIds) {
		if (article.getProjectId() != null) {
			return projectIds.contains(article.getProjectId());
		}
		if (article.getTeamId() != null) {
			return teamIds.contains(article.getTeamId());
		}
		return true;
	}

	/**
	 * The same rule as a Mongo filter, for callers that select rather than sift.
	 *
	 * <p>Returns {@code null} for a platform admin, meaning "no restriction" — an
	 * {@code $in} over every project id in the organisation would be the same
	 * answer computed expensively, and it grows with the install.
	 *
	 * <p>An empty id set is left as an empty {@code $in} rather than special-cased
	 * away: it matches nothing, which is exactly right for a viewer who reaches no
	 * projects, and the global branch still lets the org-wide space through.
	 */
	public static Criteria criteria(boolean admin, Set<String> projectIds, Set<String> teamIds) {
		if (admin) {
			return null;
		}
		return new Criteria().orOperator(
				Criteria.where("projectId").in(projectIds),
				Criteria.where("teamId").in(teamIds),
				// A global article. `is(null)` matches a missing field too, which is
				// what documents written before the field existed look like.
				new Criteria().andOperator(
						Criteria.where("projectId").is(null),
						Criteria.where("teamId").is(null)));
	}
}
