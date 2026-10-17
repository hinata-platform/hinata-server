package com.ahmadre.hinata.report;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Summarized insights, YouTrack-report style: distributions and trends. Every
 * project report requires membership of that project; the cross-project time
 * report covers only what the caller can see (see {@link ReportService}).
 */
@Tag(name = "Reports")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

	private final ReportService reports;
	private final CurrentUser currentUser;
	private final java.time.Clock clock;

	@GetMapping("/issues-by-state")
	public Map<String, Long> issuesByState(@RequestParam String projectId) {
		return reports.issuesByState(projectId, currentUser.require());
	}

	@GetMapping("/issues-by-assignee")
	public Map<String, Long> issuesByAssignee(@RequestParam String projectId) {
		return reports.issuesByAssignee(projectId, currentUser.require());
	}

	@GetMapping("/issues-by-priority")
	public Map<String, Long> issuesByPriority(@RequestParam String projectId) {
		return reports.issuesByPriority(projectId, currentUser.require());
	}

	@GetMapping("/created-vs-resolved")
	public List<ReportService.TrendPoint> createdVsResolved(@RequestParam String projectId,
			@RequestParam(defaultValue = "30") int days) {
		return reports.createdVsResolved(projectId, days, currentUser.require());
	}

	/**
	 * Everything the report page's overview draws for one project, in one answer (HIN-157): the
	 * three distributions, the trend of the last [days] days and the time per activity over the same
	 * span. One request where the page made five, each asking the same access question again.
	 */
	public record Overview(String projectId, int days, Map<String, Long> byState, Map<String, Long> byPriority,
			Map<String, Long> byAssignee, List<ReportService.TrendPoint> trend, Map<String, Integer> timePerActivity) {
	}

	/** The longest trend the overview draws. */
	static final int OVERVIEW_MAX_DAYS = 366;

	@GetMapping("/overview")
	public Overview overview(@RequestParam String projectId, @RequestParam(defaultValue = "30") int days) {
		User user = currentUser.require();
		int span = Math.clamp(days, 1, OVERVIEW_MAX_DAYS);
		LocalDate to = LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC));
		LocalDate from = to.minusDays(span - 1L);
		return new Overview(projectId, span, reports.issuesByState(projectId, user),
				reports.issuesByPriority(projectId, user), reports.issuesByAssignee(projectId, user),
				reports.createdVsResolved(projectId, span, user), reports.timePerActivity(projectId, from, to, user));
	}

	@GetMapping("/time-per-project")
	public Map<String, Integer> timePerProject(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return reports.timePerProject(from, to, currentUser.require());
	}

	@GetMapping("/time-per-activity")
	public Map<String, Integer> timePerActivity(@RequestParam String projectId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return reports.timePerActivity(projectId, from, to, currentUser.require());
	}
}
