package com.ahmadre.hinata.report;

import com.ahmadre.hinata.auth.CurrentUser;
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
