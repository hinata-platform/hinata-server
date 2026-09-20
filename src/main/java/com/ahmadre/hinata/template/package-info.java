/**
 * Project templates and deadlines that are kept as an offset from a project's event date
 * (HIN-120).
 *
 * <p>Three things live here and nothing else: the switch that decides whether the feature exists
 * on this instance, the arithmetic that turns "four weeks before the event" into a date, and the
 * service that copies a project.
 *
 * <p><b>The offset is the rule, the date is its result.</b> An issue with an offset still carries
 * a written {@code dueDate}. Everything that reads a deadline today — the board, the Gantt chart,
 * the reports, the reminder job, the published app that can no longer be changed — goes on reading
 * the same field. Recomputing a date at read time would mean teaching all of them to do the
 * arithmetic, and the store app cannot be taught anything.
 *
 * <p><b>One place computes a date.</b> {@link com.ahmadre.hinata.template.RelativeDates} is pure:
 * no Spring, no database, no clock. The preview in the form, the copy and the rewrite after an
 * event date moves all ask it, so the three cannot disagree on the one day of the year where a
 * second implementation would.
 *
 * <p>Direction is {@code template -> project, issue, availability} and never the reverse; the
 * module boundary test says so. It reads {@code availability} for one thing only: which days a
 * holiday calendar marks, so a working-day offset can skip them. That is calendar data, not
 * anybody's absence, which is why the package is a reader of holidays and not of capacity.
 *
 * <p>Everything here is behind the {@code project_templates} feature flag
 * ({@link com.ahmadre.hinata.template.ProjectTemplateSettings}), which is off on a fresh instance.
 */
package com.ahmadre.hinata.template;
