package com.ahmadre.hinata.article;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The outline of a team's knowledge-base pages: what a Team-Admin picks from when
 * opening pages to a member. Titles and tree only, no bodies, and searchable, so
 * the picker neither downloads a wiki to show its titles nor loses a page to a cap.
 */
@Tag(name = "Knowledge Base")
@RestController
@RequestMapping("/api/v1/teams/{teamId}/pages")
@RequiredArgsConstructor
public class TeamPagesController {

	private final ArticleService articles;
	private final TeamRepository teams;
	private final CurrentUser currentUser;

	@GetMapping
	public ResponseEntity<List<ArticleService.PageRef>> outline(@PathVariable String teamId,
			@RequestParam(required = false) String q) {
		User user = currentUser.require();
		com.ahmadre.hinata.team.Team team = teams.findById(teamId)
				.filter(candidate -> candidate.isMember(user.getId()))
				// Whether a team exists is not a stranger's to learn.
				.orElseThrow(() -> ApiException.notFound("team"));
		List<ArticleService.PageRef> found = articles.outlineOfTeam(user, team, q);
		if (found.size() > ArticleService.OUTLINE_CAP) {
			return ResponseEntity.ok().header(ArticleController.TRUNCATED, "true")
					.body(found.subList(0, ArticleService.OUTLINE_CAP));
		}
		return ResponseEntity.ok(found);
	}
}
