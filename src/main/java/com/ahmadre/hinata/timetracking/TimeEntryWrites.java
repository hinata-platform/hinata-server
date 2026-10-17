package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What every write path of an entry tells the rest of the module (HIN-152, HIN-153): that a
 * project's and an issue's recorded time moved.
 *
 * <p>The usage cache is told directly and forgets the project at once, so a bar drawn right after a
 * save shows the save. Two listeners hear the event: the live budget alerts measure the project and
 * the issue again, after the request, so the leads hear about a threshold the moment it is crossed
 * rather than at the next hourly scan; and billing forgets the project's money figure (HIN-155).
 */
@Component
@RequiredArgsConstructor
public class TimeEntryWrites {

	private final TimeUsageCache cache;
	private final ApplicationEventPublisher events;

	/** The projects and issues whose recorded time just moved. */
	public record Touched(Set<String> projectIds, Set<String> issueIds) {
	}

	/** Entries went whose projects are not known: everything held about recorded time is stale. */
	public record TouchedUnknown() {
	}

	/** One entry was written or removed; either id may be null. */
	public void touched(String projectId, String issueId) {
		Set<String> projects = new LinkedHashSet<>();
		Set<String> issues = new LinkedHashSet<>();
		if (projectId != null) projects.add(projectId);
		if (issueId != null) issues.add(issueId);
		publish(projects, issues);
	}

	/** Several entries at once: an import. */
	public void touched(Collection<WorkItem> items) {
		publish(items.stream().map(WorkItem::getProjectId).filter(Objects::nonNull)
						.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
				items.stream().map(WorkItem::getIssueId).filter(Objects::nonNull)
						.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
	}

	/**
	 * Entries went whose projects are not known without reading them: an import taken back. The
	 * cache is dropped whole; nothing is measured, because removing time can only bring a project
	 * back under its thresholds, which the next write or the hourly scan settles.
	 */
	public void touchedUnknown() {
		cache.forgetAll();
		events.publishEvent(new TouchedUnknown());
	}

	private void publish(Set<String> projects, Set<String> issues) {
		projects.forEach(cache::forget);
		if (!projects.isEmpty() || !issues.isEmpty()) {
			events.publishEvent(new Touched(Set.copyOf(projects), Set.copyOf(issues)));
		}
	}
}
