package com.ahmadre.hinata.search;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global search / command-palette backend (⌘K). */
@Tag(name = "Search")
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

	private final SearchService searchService;
	private final CurrentUser currentUser;

	/**
	 * Unified search across issues, projects, people, boards and knowledge.
	 *
	 * @param q        free-text query; blank returns just the category counts
	 * @param scope    {@code all} (default) or a single category name
	 * @param archived search archived (soft-deleted) issues and archived
	 *                 projects instead of active ones — triggered by the
	 *                 palette's locale-aware "archived"/"archiviert" keyword
	 */
	@GetMapping
	public SearchResponse search(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String scope,
			@RequestParam(defaultValue = "false") boolean archived) {
		currentUser.require();
		return searchService.search(q, scope, archived);
	}
}
