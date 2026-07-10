package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Weekly workspace digest: every Monday morning, each active user gets a short
 * summary of their open assigned work and unread notifications. Delivery is gated
 * by the recipient's {@code digest} channel preference (default: e-mail on, push
 * off). Users with nothing to report are skipped so the digest never becomes noise.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyDigestJob {

	private final UserRepository users;
	private final IssueRepository issues;
	private final NotificationRepository notifications;
	private final NotificationService notificationService;

	@Scheduled(cron = "0 0 7 * * MON")
	public void send() {
		int sent = 0;
		for (User user : users.findByActiveIsTrue()) {
			long openAssigned = issues.findByAssigneeIdsContainsOrderByCreatedAtDesc(user.getId())
					.stream().filter(i -> i.getResolvedAt() == null).count();
			long unread = notifications.countByUserIdAndReadFalse(user.getId());
			if (openAssigned == 0 && unread == 0) {
				continue; // nothing worth a digest this week
			}
			boolean de = "de".equalsIgnoreCase(user.getLocale());
			String title = de ? "Deine Wochenübersicht" : "Your weekly summary";
			String body = de
					? "Du hast " + openAssigned + " offene dir zugewiesene Vorgänge und "
							+ unread + " ungelesene Benachrichtigungen."
					: "You have " + openAssigned + " open issues assigned to you and "
							+ unread + " unread notifications.";
			notificationService.notifyDigest(user, title, body, "/");
			sent++;
		}
		if (sent > 0) {
			log.info("Sent weekly digest to {} user(s)", sent);
		}
	}
}
