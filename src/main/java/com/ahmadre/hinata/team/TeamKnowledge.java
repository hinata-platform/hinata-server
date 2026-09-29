package com.ahmadre.hinata.team;

import java.util.Collection;
import java.util.Set;

/**
 * The question {@link TeamService} asks the knowledge base when a Team-Admin opens
 * pages to a member: which of these ids are pages of this team. Answered in the
 * {@code article} package, which already depends on {@code team}; asking it the
 * other way round would make the two packages depend on each other.
 */
public interface TeamKnowledge {

	/** The subset of {@code articleIds} that are pages of {@code teamId}. */
	Set<String> pagesOf(String teamId, Collection<String> articleIds);
}
