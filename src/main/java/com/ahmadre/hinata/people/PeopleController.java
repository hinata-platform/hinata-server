package com.ahmadre.hinata.people;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A person's profile, the page a person in the search, an avatar or a mention opens (HIN-165). */
@Tag(name = "People")
@RestController
@RequestMapping("/api/v1/people")
@RequiredArgsConstructor
public class PeopleController {

	private final PersonProfileService profiles;
	private final CurrentUser currentUser;

	@Operation(summary = "A person's profile",
			description = "Directory data, shared teams and projects, the time zone and, with sight, today's absence. "
					+ "404 for a missing or deactivated account (administrators see deactivated ones).")
	@GetMapping("/{id}")
	public PersonProfile get(@PathVariable String id) {
		return profiles.profile(currentUser.require(), id);
	}
}
