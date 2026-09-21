package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/time/reports} — summary, detailed and workload reports (HIN-93). Every route runs
 * in the reader's own scope; see {@link TimeReportScope}.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/reports")
@RequiredArgsConstructor
public class TimeReportController {

	private final TimeReportService reports;
	private final TimeWorkloadReport workload;
	private final CurrentUser currentUser;

	@Operation(summary = "Totals and one page of groups")
	@GetMapping("/summary")
	public TimeReportService.Summary summary(@ModelAttribute TimeReportQuery query,
			@RequestParam(defaultValue = "PROJECT") TimeReportService.GroupBy groupBy,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		var viewer = currentUser.require();
		return reports.summary(viewer, reports.filter(viewer, query), groupBy, page, size);
	}

	@Operation(summary = "One page of entries, newest first")
	@GetMapping("/detailed")
	public Page<TimeReportService.EntryRow> detailed(@ModelAttribute TimeReportQuery query,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		var viewer = currentUser.require();
		return reports.detailed(viewer, reports.filter(viewer, query), page, size);
	}

	@Operation(summary = "Booked time against capacity, per person, by name")
	@GetMapping("/workload")
	public TimeWorkloadReport.Workload workload(@ModelAttribute TimeReportQuery query,
			@RequestParam(required = false) String teamId, @RequestParam(required = false) String projectId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		var viewer = currentUser.require();
		return workload.workload(viewer, reports.filter(viewer, query), teamId, projectId, page, size);
	}
}
