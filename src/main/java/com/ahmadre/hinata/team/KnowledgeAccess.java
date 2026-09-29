package com.ahmadre.hinata.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of a team's knowledge-base pages a member may read, embedded in
 * {@link TeamMembership} next to {@link ProjectAccess}.
 * <ul>
 *   <li>{@code ALL}  – every page the team owns.</li>
 *   <li>{@code NONE} – no page. The default, and what a membership written before
 *       this field existed reads as: pages are opened on purpose, never by joining.</li>
 *   <li>{@code SOME} – the pages in {@link #articleIds} and everything filed under
 *       them. Always pages of this team.</li>
 * </ul>
 * A Team-Admin reads every page of the team whatever this says.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeAccess {

	/** More ids than a person picks by hand; the bound keeps the grant query small. */
	public static final int MAX_ARTICLES = 200;

	@Builder.Default
	private ProjectAccess.Scope scope = ProjectAccess.Scope.NONE;

	/** Only meaningful when {@code scope} is {@code SOME}. */
	@Builder.Default
	private List<String> articleIds = new ArrayList<>();

	/**
	 * How many pages a SOME grant names, for a reader who is shown the number but not
	 * the pages (see {@link #redacted()}). Never stored.
	 */
	@org.springframework.data.annotation.Transient
	@com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
	private Integer count;

	public int getCount() {
		return count != null ? count : articleIds == null ? 0 : articleIds.size();
	}

	/**
	 * This grant as somebody else's reads it: the scope and how many pages, not which.
	 * Which pages a colleague may read is the Team-Admins' business, and the ids would
	 * name pages the reader may not see.
	 */
	public KnowledgeAccess redacted() {
		return KnowledgeAccess.builder().scope(scope).articleIds(new ArrayList<>()).count(getCount()).build();
	}

	public static KnowledgeAccess all() {
		return KnowledgeAccess.builder().scope(ProjectAccess.Scope.ALL).build();
	}

	public static KnowledgeAccess none() {
		return KnowledgeAccess.builder().scope(ProjectAccess.Scope.NONE).build();
	}

	public static KnowledgeAccess some(List<String> articleIds) {
		return KnowledgeAccess.builder().scope(ProjectAccess.Scope.SOME)
				.articleIds(articleIds != null ? new ArrayList<>(articleIds) : new ArrayList<>())
				.build();
	}
}
