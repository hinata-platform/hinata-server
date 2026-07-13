package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.weeklysummary.WeeklySummaryService;
import com.ahmadre.hinata.weeklysummary.WeeklySummaryService.WeeklySummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Weekly workspace digest: every Monday morning each active user gets their
 * Weekly Summary — the team's work over the past week and their own upcoming
 * to-dos — as an in-app notice (linking to the in-app summary page) and a rich
 * templated e-mail. Delivery is gated by the recipient's {@code digest} channel
 * preference (default: e-mail on, push off). Users with nothing to report are
 * skipped so the digest never becomes noise. The aggregation is shared with the
 * in-app {@code GET /api/v1/weekly-summary} so the page and the mail never diverge.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyDigestJob {

	/** Contributors / to-dos surfaced inline in the e-mail (the page shows more). */
	private static final int MAIL_CONTRIBUTORS = 5;

	private final UserRepository users;
	private final WeeklySummaryService weeklySummary;
	private final NotificationService notificationService;

	@Scheduled(cron = "0 0 7 * * MON")
	public void send() {
		int sent = 0;
		for (User user : users.findByActiveIsTrue()) {
			WeeklySummary summary = weeklySummary.forUser(user);
			if (summary.isEmpty()) {
				continue; // nothing worth a digest this week
			}
			boolean de = "de".equalsIgnoreCase(user.getLocale());
			String title = de ? "Deine Wochenübersicht" : "Your weekly summary";
			String body = summaryBody(summary, de);
			notificationService.notifyWeeklySummary(user, title, body, mailModel(user, summary, de));
			sent++;
		}
		if (sent > 0) {
			log.info("Sent weekly summary to {} user(s)", sent);
		}
	}

	/** One-line teaser for the in-app (bell) notice and the push. */
	private String summaryBody(WeeklySummary s, boolean de) {
		long done = s.team().completed();
		long todo = s.upcoming().total();
		long overdue = s.upcoming().overdue();
		if (de) {
			String base = "Das Team hat letzte Woche " + done + " Vorgänge abgeschlossen. "
					+ todo + " To-Dos stehen an";
			return overdue > 0 ? base + " (" + overdue + " überfällig)." : base + ".";
		}
		String base = "Your team closed " + done + " issues last week. "
				+ todo + " to-dos coming up";
		return overdue > 0 ? base + " (" + overdue + " overdue)." : base + ".";
	}

	/** Flat model for {@code email/weekly-summary.html} (Thymeleaf reads map keys). */
	private Map<String, Object> mailModel(User user, WeeklySummary s, boolean de) {
		Locale locale = de ? Locale.GERMAN : Locale.ENGLISH;
		DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("MMM d", locale);

		Map<String, Object> model = new HashMap<>();
		model.put("locale", de ? "de" : "en");
		model.put("displayName", user.getDisplayName());
		model.put("weekRange", s.weekStart().format(dayFmt) + " – " + s.weekEnd().format(dayFmt));
		model.put("completed", s.team().completed());
		model.put("created", s.team().created());
		model.put("focusLabel", focusLabel(s.team().focusMinutes()));

		List<Map<String, Object>> contributors = new ArrayList<>();
		s.team().contributors().stream().limit(MAIL_CONTRIBUTORS).forEach(c -> {
			Map<String, Object> m = new HashMap<>();
			m.put("displayName", c.displayName());
			m.put("initials", initials(c.displayName()));
			m.put("completed", c.completed());
			contributors.add(m);
		});
		model.put("contributors", contributors);

		List<Map<String, Object>> highlights = new ArrayList<>();
		for (Issue h : s.team().highlights()) {
			Map<String, Object> m = new HashMap<>();
			m.put("readableId", h.getReadableId());
			m.put("title", h.getTitle());
			highlights.add(m);
		}
		model.put("highlights", highlights);

		if (s.team().sprint() != null) {
			var sp = s.team().sprint();
			Map<String, Object> m = new HashMap<>();
			m.put("name", sp.name());
			m.put("day", sp.day());
			m.put("days", sp.days());
			m.put("issuesDone", sp.issuesDone());
			m.put("issuesTotal", sp.issuesTotal());
			model.put("sprint", m);
		} else {
			model.put("sprint", null);
		}

		LocalDate today = s.weekEnd();
		List<Map<String, Object>> upcoming = new ArrayList<>();
		for (Issue t : s.upcoming().items()) {
			boolean overdue = t.getDueDate() != null && t.getDueDate().isBefore(today);
			Map<String, Object> m = new HashMap<>();
			m.put("readableId", t.getReadableId());
			m.put("title", t.getTitle());
			m.put("due", t.getDueDate() != null ? t.getDueDate().format(dayFmt) : null);
			m.put("overdue", overdue);
			upcoming.add(m);
		}
		model.put("upcoming", upcoming);
		model.put("upcomingTotal", s.upcoming().total());
		model.put("upcomingShown", s.upcoming().items().size());
		model.put("overdueCount", s.upcoming().overdue());
		return model;
	}

	/** "3h 20m" / "45m" / "0m" from a minute count. */
	private static String focusLabel(int minutes) {
		if (minutes <= 0) {
			return "0m";
		}
		int h = minutes / 60;
		int m = minutes % 60;
		if (h == 0) {
			return m + "m";
		}
		return m == 0 ? h + "h" : h + "h " + m + "m";
	}

	/** Up to two initials from a display name, for the avatar chip. */
	private static String initials(String name) {
		if (name == null || name.isBlank()) {
			return "?";
		}
		String[] parts = name.trim().split("\\s+");
		String first = parts[0].substring(0, 1);
		String second = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
		return (first + second).toUpperCase(Locale.ROOT);
	}
}
