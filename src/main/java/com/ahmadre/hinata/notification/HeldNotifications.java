package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mail and push that waited for a person's notification window (HIN-131).
 *
 * <p>Outside the window a notification is written to the bell at once and marked
 * {@link Notification#isHeld() held}. When the window opens, whatever is still unread
 * goes out as one mail and one push; what the person already read in the app is simply
 * released, because telling them again would be noise. Nothing waits longer than the next
 * window, and a person who switched a channel off in the meantime is not reached on it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeldNotifications {

	/** Rows the mail lists; the rest is counted and waits in the app. */
	static final int MAIL_ITEMS = 20;

	/** Notifications read per person and sweep; a night's worth, never a whole history. */
	private static final int PER_PERSON = 200;

	/** One line of the summary mail. */
	public record Item(String title, String body, String link) {
	}

	private final MongoTemplate mongo;
	private final UserRepository users;
	private final NotificationDays days;
	private final MailService mail;
	private final PushService push;
	private final GatewayService gateway;
	private final UserWords words;

	/**
	 * Marks the unread notifications about one issue as waiting for the mail — for a change
	 * digest that came due while its recipient's window was closed. The bell entries are what
	 * the summary mail lists, so the changes are not lost with the digest.
	 */
	public void holdEmailFor(String userId, String issueKey, Instant since) {
		Criteria criteria = Criteria.where("userId").is(userId).and("read").is(false)
				.and("link").regex("^" + Pattern.quote("/issues/" + issueKey) + "(\\?|$)");
		if (since != null) criteria = criteria.and("createdAt").gte(since);
		mongo.updateMulti(new Query(criteria),
				new Update().set("held", true).set("heldEmail", true), Notification.class);
	}

	/**
	 * Sends what waited for everybody whose window is open now.
	 *
	 * @param limit the most people handled in one sweep
	 * @return how many people were sent something
	 */
	public int sweep(int limit) {
		List<String> waiting = mongo.findDistinct(new Query(Criteria.where("held").is(true)),
				"userId", Notification.class, String.class);
		int sent = 0;
		for (String userId : waiting.stream().limit(limit).toList()) {
			try {
				if (release(userId)) sent++;
			}
			catch (RuntimeException ex) {
				// One person's broken row must not stop everybody else's morning.
				log.warn("Sending held notifications to {} failed", userId, ex);
			}
		}
		return sent;
	}

	/** Sends one person's held mail and push if their window is open; true when anything went out. */
	boolean release(String userId) {
		User user = users.findById(userId).orElse(null);
		if (user == null || !user.isActive()) {
			clear(userId);
			return false;
		}
		NotificationDays.Gate gate = days.gate(user);
		if (!gate.open()) return false;

		Query query = new Query(Criteria.where("held").is(true).and("userId").is(userId))
				.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(PER_PERSON);
		List<Notification> held = mongo.find(query, Notification.class);
		clear(userId);

		List<Notification> unread = held.stream().filter(n -> !n.isRead()).toList();
		NotificationPreferences prefs = gate.prefs();
		List<Notification> mails = prefs.isEmailEnabled()
				? unread.stream().filter(Notification::isHeldEmail).toList() : List.of();
		boolean pushes = prefs.isPushEnabled() && unread.stream().anyMatch(Notification::isHeldPush);

		if (!mails.isEmpty()) {
			mail.sendTemplate(user.getEmail(), mail.subjectPrefix() + words.of(user, "email.held.subject"),
					"email/held-summary", model(user, mails));
		}
		if (pushes) {
			push.sendToUser(user.getId(), words.of(user, "notify.held.pushTitle"),
					words.of(user, "notify.held.pushBody"), "/notifications");
		}
		return !mails.isEmpty() || pushes;
	}

	private Map<String, Object> model(User user, List<Notification> mails) {
		Map<String, Object> model = new HashMap<>();
		model.put("locale", words.localeOf(user).toLanguageTag());
		model.put("displayName", user.getDisplayName());
		model.put("items", mails.stream().limit(MAIL_ITEMS)
				.map(n -> new Item(n.getTitle(), n.getBody(), link(n.getLink())))
				.toList());
		model.put("more", Math.max(0, mails.size() - MAIL_ITEMS));
		model.put("ctaLink", link("/notifications"));
		return model;
	}

	private String link(String link) {
		return link == null || link.isBlank() ? gateway.relayLink("/notifications", null)
				: gateway.relayLink(link, null);
	}

	private void clear(String userId) {
		mongo.updateMulti(new Query(Criteria.where("held").is(true).and("userId").is(userId)),
				new Update().set("held", false).set("heldEmail", false).set("heldPush", false),
				Notification.class);
	}
}
