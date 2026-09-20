package com.ahmadre.hinata.template;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueDeadlinePolicy;
import com.ahmadre.hinata.project.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * The module's answer to {@link IssueDeadlinePolicy}: an issue's written dates, recomputed from
 * its offsets and its project's event date.
 *
 * <p>Nothing happens for an issue without offsets, which is nearly every issue, so this sits on
 * the write path of the whole product for the cost of two null checks.
 *
 * <p>A project with no event date leaves the dates exactly as they are rather than clearing them.
 * Clearing would mean that removing an event date silently emptied a hundred deadlines, and the
 * offsets are still there to recompute from the moment a date comes back. It is also what makes a
 * template sensible: its issues carry the rule and no dates at all.
 */
@Component
@RequiredArgsConstructor
public class ProjectDeadlines implements IssueDeadlinePolicy {

	private final ProjectTemplateSettings settings;
	private final HolidayCalendars calendars;

	@Override
	public boolean offsetsEnabled() {
		return settings.enabled();
	}

	@Override
	public void resolveDates(Issue issue, Project project) {
		// Asked first, and not only for tidiness: an instance that switched the module off would
		// otherwise go on rewriting the dates of every issue that still carries a rule, for as
		// long as the data exists and with no way for an administrator to stop it.
		if (!settings.enabled()) {
			return;
		}
		if (issue == null || (issue.getStartOffset() == null && issue.getDueOffset() == null)) {
			return;
		}
		if (project == null || project.getEventDate() == null) {
			return;
		}
		WorkdayCalendar calendar = calendars.of(project);
		LocalDate start = RelativeDates.resolve(project.getEventDate(),
				issue.getStartOffset(), calendar);
		if (start != null) {
			issue.setStartDate(start);
		}
		LocalDate due = RelativeDates.resolve(project.getEventDate(),
				issue.getDueOffset(), calendar);
		if (due != null) {
			issue.setDueDate(due);
		}
	}
}
