package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mails scheduled reports (HIN-93): once per covered period and recipient, each recipient's
 * summary computed in that recipient's own scope.
 *
 * <p>A weekly report goes out on its day and covers the seven days before it; a monthly one on
 * the first and covers the month before. Each run looks for the latest moment every schedule was
 * due and claims ({@link TimeMarks}) one mark per recipient for that period — so a run the server
 * missed is caught up an hour later, two runs at once send once, and nothing is sent twice.
 *
 * <p>The owner is not a recipient by being the owner: somebody who schedules a report for their
 * team receives it only if they put themselves on the list. The log counts reports, never people:
 * "sent 1 at 17:40" says whom it means.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TimeReportMails {

	/** Schedules read per round trip. */
	static final int PAGE = 200;

	/** Groups a mail lists; the rest is one line pointing into the app. */
	static final int GROUPS_SHOWN = 5;

	private final MongoTemplate mongo;
	private final TimeReportService reports;
	private final TimeSavedReportService saved;
	private final TimeMarks marks;
	private final NotificationService notifications;
	private final UserRepository users;
	private final UserWords words;
	private final Clock clock;

	/** Sends what is due and returns how many reports went out to at least one person. */
	int run() {
		int reportsSent = 0;
		String after = null;
		while (true) {
			Criteria criteria = Criteria.where("schedule").exists(true);
			if (after != null) {
				criteria = criteria.and("_id").gt(new org.bson.types.ObjectId(after));
			}
			List<TimeSavedReport> page = mongo.find(Query.query(criteria)
					.with(Sort.by(Sort.Order.asc("_id"))).limit(PAGE), TimeSavedReport.class);
			for (TimeSavedReport report : page) {
				try {
					if (send(report) > 0) {
						reportsSent++;
					}
				}
				catch (RuntimeException ex) {
					log.warn("[time] scheduled report {} failed: {}", report.getId(), ex.toString());
				}
			}
			if (page.size() < PAGE) {
				return reportsSent;
			}
			after = page.get(page.size() - 1).getId();
		}
	}

	/** The mails of one report for the period it was last due; the number of recipients reached. */
	int send(TimeSavedReport report) {
		TimeSavedReport.Schedule schedule = report.getSchedule();
		ZoneId zone = zoneOf(schedule);
		LocalDate due = lastDue(schedule, clock.instant().atZone(zone));
		// A schedule set on a Wednesday for Mondays starts with the next Monday, not the last one.
		if (schedule.getSince() != null && due.atTime(hourOf(schedule), 0).atZone(zone).toInstant()
				.isBefore(schedule.getSince())) {
			return 0;
		}
		LocalDate from = schedule.getCadence() == TimeSavedReport.Cadence.WEEKLY ? due.minusDays(7)
				: due.minusMonths(1);
		LocalDate to = due.minusDays(1);
		Map<String, String> byKey = new LinkedHashMap<>();
		for (String recipient : schedule.getRecipients()) {
			byKey.put(TimeMarks.reportKey(report.getId(), from, recipient), recipient);
		}
		Set<String> claimed = marks.claimAll(byKey.keySet());
		int reached = 0;
		for (String key : claimed) {
			User recipient = users.findById(byKey.get(key)).filter(User::isActive).orElse(null);
			if (recipient == null) {
				continue;
			}
			try {
				mail(report, recipient, from, to);
				reached++;
			}
			catch (RuntimeException ex) {
				// Given back, so the next run tries this person again within the period; the others
				// of this run still get theirs.
				marks.release(List.of(key));
				log.warn("[time] a mail of scheduled report {} failed: {}", report.getId(), ex.toString());
			}
		}
		return reached;
	}

	/**
	 * The latest day the schedule was due on or before [now]: the day has come and its hour has
	 * passed.
	 */
	static LocalDate lastDue(TimeSavedReport.Schedule schedule, ZonedDateTime now) {
		int hour = hourOf(schedule);
		LocalDate today = now.toLocalDate();
		LocalDate candidate = schedule.getCadence() == TimeSavedReport.Cadence.WEEKLY
				? today.with(TemporalAdjusters.previousOrSame(schedule.getDayOfWeek()))
				: today.withDayOfMonth(1);
		if (candidate.equals(today) && now.getHour() < hour) {
			candidate = schedule.getCadence() == TimeSavedReport.Cadence.WEEKLY ? candidate.minusWeeks(1)
					: candidate.minusMonths(1);
		}
		return candidate;
	}

	private void mail(TimeSavedReport report, User recipient, LocalDate from, LocalDate to) {
		TimeReportFilter filter = reports.filter(recipient, TimeSavedReportService.query(report.getConfig(), from, to));
		TimeReportService.GroupBy groupBy = report.getConfig().getGroupBy() == null ? TimeReportService.GroupBy.PROJECT
				: report.getConfig().getGroupBy();
		TimeReportService.Summary summary = reports.summary(recipient, filter, groupBy, 0, GROUPS_SHOWN);
		Locale locale = words.localeOf(recipient);
		String period = words.date(locale, from) + " – " + words.date(locale, to);
		String total = hours(summary.totals().minutes());

		List<Map<String, Object>> groups = new ArrayList<>();
		for (TimeReportService.Group group : summary.groups().getContent()) {
			Map<String, Object> row = new HashMap<>();
			row.put("label", label(group, groupBy, locale));
			row.put("hours", hours(group.minutes()));
			groups.add(row);
		}
		Map<String, Object> model = new HashMap<>();
		model.put("locale", locale.getLanguage());
		model.put("displayName", recipient.getDisplayName());
		model.put("reportName", report.getName());
		model.put("period", period);
		model.put("total", total);
		model.put("billable", hours(summary.totals().billableMinutes()));
		model.put("entries", summary.totals().entries());
		model.put("groups", groups);
		model.put("groupsLabel", words.in(locale, "export.report.section.summary",
				words.in(locale, "export.report.group." + groupBy.name())));
		model.put("moreGroups", Math.max(0, summary.groups().getTotalElements() - groups.size()));
		notifications.notifyTimeReport(recipient, words.in(locale, "notify.timeReport.title", report.getName()),
				words.in(locale, "notify.timeReport.body", total, period), model,
				"/time/reports?saved=" + report.getId());
	}

	private String label(TimeReportService.Group group, TimeReportService.GroupBy groupBy, Locale locale) {
		if (group.key() == null) {
			return words.in(locale, "export.report.none." + groupBy.name());
		}
		if (groupBy == TimeReportService.GroupBy.DAY || groupBy == TimeReportService.GroupBy.WEEK
				|| groupBy == TimeReportService.GroupBy.MONTH) {
			return words.date(locale, LocalDate.parse(group.key()));
		}
		return group.label() != null ? group.label() : group.key();
	}

	private static int hourOf(TimeSavedReport.Schedule schedule) {
		return schedule.getHour() == null ? 7 : schedule.getHour();
	}

	private static String hours(long minutes) {
		return String.format(Locale.ROOT, "%d:%02d", minutes / 60, Math.abs(minutes % 60));
	}

	private static ZoneId zoneOf(TimeSavedReport.Schedule schedule) {
		try {
			return schedule.getZone() == null ? ZoneOffset.UTC : ZoneId.of(schedule.getZone());
		}
		catch (DateTimeException ex) {
			return ZoneOffset.UTC;
		}
	}
}
