package com.ahmadre.hinata.dashboard;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Thin HTTP layer for the dashboard — all cross-domain aggregation lives in
 * {@link DashboardService}.
 */
@Tag(name = "Dashboard")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

	private final DashboardService dashboardService;
	private final CurrentUser currentUser;

	/**
	 * Aggregated dashboard. Query params (sent for live preview while the user is
	 * in edit mode) override the saved personalisation; when absent, the caller's
	 * persisted {@link DashboardPrefs} drive the scope and pinned hero board.
	 *
	 * @param projectIds scope for aggregated data; empty ⇒ all visible projects
	 * @param teamIds    scope for the ranking; empty ⇒ all teams
	 * @param boardId    pinned hero board; blank ⇒ auto-pick
	 */
	@GetMapping
	public DashboardService.DashboardData dashboard(
			@RequestParam(required = false) String boardId,
			@RequestParam(required = false) List<String> projectIds,
			@RequestParam(required = false) List<String> teamIds) {
		User user = currentUser.require();
		return dashboardService.dashboard(boardId, projectIds, teamIds, user);
	}

	/** Persist the caller's dashboard personalisation. */
	@PutMapping("/prefs")
	public DashboardService.DashboardPrefsDto savePrefs(
			@RequestBody DashboardService.DashboardPrefsDto body) {
		return dashboardService.savePrefs(body, currentUser.require());
	}
}
