package com.ahmadre.hinata.weeklysummary;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's Weekly Summary: the team's week behind + the caller's week ahead. */
@Tag(name = "Weekly Summary")
@RestController
@RequestMapping("/api/v1/weekly-summary")
@RequiredArgsConstructor
public class WeeklySummaryController {

	private final WeeklySummaryService weeklySummary;
	private final CurrentUser currentUser;

	@GetMapping
	public WeeklySummaryService.WeeklySummary get() {
		User user = currentUser.require();
		return weeklySummary.forUser(user);
	}
}
