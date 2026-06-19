package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TimeTrackingService {

	private final WorkItemRepository workItems;
	private final IssueRepository issues;

	public WorkItem add(String issueId, WorkItem item) {
		Issue issue = issues.findById(issueId)
				.or(() -> issues.findByReadableIdIgnoreCase(issueId))
				.orElseThrow(() -> ApiException.notFound("issue"));
		if (item.getDurationMinutes() <= 0 || item.getDurationMinutes() > 24 * 60) {
			throw ApiException.badRequest("error.time.invalidDuration");
		}
		item.setIssueId(issue.getId());
		item.setProjectId(issue.getProjectId());
		if (item.getDate() == null) {
			item.setDate(LocalDate.now());
		}
		WorkItem saved = workItems.save(item);
		syncSpentTime(issue);
		return saved;
	}

	public void delete(String workItemId, String requesterId, boolean isAdmin) {
		WorkItem item = workItems.findById(workItemId)
				.orElseThrow(() -> ApiException.notFound("workItem"));
		if (!isAdmin && !item.getUserId().equals(requesterId)) {
			throw ApiException.forbidden("error.time.deleteOwnOnly");
		}
		workItems.delete(item);
		issues.findById(item.getIssueId()).ifPresent(this::syncSpentTime);
	}

	private void syncSpentTime(Issue issue) {
		int total = workItems.findByIssueIdOrderByDateDesc(issue.getId()).stream()
				.mapToInt(WorkItem::getDurationMinutes).sum();
		issue.setSpentMinutes(total);
		issues.save(issue);
	}

	public record TimesheetRow(String userId, String projectId, Map<LocalDate, Integer> minutesPerDay,
			int totalMinutes) {
	}

	/** Timesheet matrix: per user+project row, minutes per day in the range. */
	public List<TimesheetRow> timesheet(LocalDate from, LocalDate to, String userId, String projectId) {
		if (from.isAfter(to) || from.plusDays(92).isBefore(to)) {
			throw ApiException.badRequest("error.time.invalidRange");
		}
		List<WorkItem> items;
		if (userId != null) {
			items = workItems.findByUserIdAndDateBetween(userId, from, to);
		}
		else if (projectId != null) {
			items = workItems.findByProjectIdAndDateBetween(projectId, from, to);
		}
		else {
			items = workItems.findByDateBetween(from, to);
		}
		Map<String, List<WorkItem>> grouped = items.stream()
				.collect(Collectors.groupingBy(item -> item.getUserId() + "|" + item.getProjectId()));
		List<TimesheetRow> rows = new ArrayList<>();
		for (List<WorkItem> group : grouped.values()) {
			Map<LocalDate, Integer> perDay = new TreeMap<>();
			int total = 0;
			for (WorkItem item : group) {
				perDay.merge(item.getDate(), item.getDurationMinutes(), Integer::sum);
				total += item.getDurationMinutes();
			}
			rows.add(new TimesheetRow(group.get(0).getUserId(), group.get(0).getProjectId(),
					perDay, total));
		}
		rows.sort(Comparator.comparing(TimesheetRow::userId));
		return rows;
	}
}
