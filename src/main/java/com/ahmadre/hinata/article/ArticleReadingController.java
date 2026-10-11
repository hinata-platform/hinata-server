package com.ahmadre.hinata.article;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** The caller's favourite pages and the pages they opened last. */
@Tag(name = "Knowledge Base")
@RestController
@RequestMapping("/api/v1/articles")
@RequiredArgsConstructor
public class ArticleReadingController {

	private final ArticleService service;
	private final ArticleReading reading;
	private final CurrentUser currentUser;

	@GetMapping("/marks")
	public ArticleReading.Marks marks() {
		return reading.of(currentUser.require());
	}

	@PutMapping("/{id}/star")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void star(@PathVariable String id) {
		User user = currentUser.require();
		reading.star(service.readable(id, user), user, true);
	}

	@DeleteMapping("/{id}/star")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unstar(@PathVariable String id) {
		User user = currentUser.require();
		reading.star(service.readable(id, user), user, false);
	}

	/** Called by the app when a page is opened; reading a page through the API records nothing by itself. */
	@PostMapping("/{id}/view")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void viewed(@PathVariable String id) {
		User user = currentUser.require();
		reading.viewed(service.readable(id, user), user);
	}
}
