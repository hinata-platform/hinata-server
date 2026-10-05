package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A person's calendar subscriptions and the events kept from them, in their own data export
 * (HIN-94).
 *
 * <p>The subscriptions without their address: the address is a credential to somebody else's
 * service, and the export is a file that travels. The host stands in for it. Events up to
 * {@value #EVENT_CAP} in the JSON and {@value #TABLE_ROWS} in the PDF, each with a flag or a line
 * when cut; there are at most ten subscriptions, so they are never cut.
 *
 * <p>Regardless of whether the import is switched on today: what is stored about somebody is
 * exported whatever an administrator has switched off since.
 */
@Component
@RequiredArgsConstructor
public class CalendarPersonalData implements PersonalDataExport {

	static final int EVENT_CAP = 5_000;
	static final int TABLE_ROWS = 500;

	private final MongoTemplate mongo;
	private final UserWords words;

	@Override
	public String key() {
		return "calendarSubscriptions";
	}

	@Override
	public Object data(User user) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("subscriptions", subscriptionsOf(user).stream().map(CalendarPersonalData::subscription).toList());
		List<CalendarEvent> events = eventsOf(user, EVENT_CAP + 1);
		out.put("events", events.stream().limit(EVENT_CAP).map(CalendarPersonalData::event).toList());
		out.put("eventsTruncated", events.size() > EVENT_CAP);
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<CalendarSubscription> subscriptions = subscriptionsOf(user);
		if (subscriptions.isEmpty()) {
			return List.of();
		}
		Map<String, String> names = new LinkedHashMap<>();
		subscriptions.forEach(subscription -> names.put(subscription.getId(), subscription.getName()));
		List<Table> tables = new ArrayList<>();
		tables.add(new Table(t(locale, "export.pdf.calendar.subscriptions"),
				List.of(t(locale, "export.pdf.calendar.name"), t(locale, "export.pdf.calendar.host"),
						t(locale, "export.pdf.calendar.status"), t(locale, "export.pdf.calendar.createdAt")),
				new float[]{4, 4, 2, 3},
				subscriptions.stream().map(subscription -> List.of(subscription.getName(),
						String.valueOf(subscription.getHostMasked()), subscription.getLastStatus().name(),
						PersonalDataExport.instant(subscription.getCreatedAt()))).toList(),
				null));
		List<CalendarEvent> events = eventsOf(user, TABLE_ROWS + 1);
		tables.add(new Table(t(locale, "export.pdf.calendar.events"),
				List.of(t(locale, "export.pdf.calendar.startsAt"), t(locale, "export.pdf.calendar.endsAt"),
						t(locale, "export.pdf.calendar.summary"), t(locale, "export.pdf.calendar.name")),
				new float[]{3, 3, 5, 3},
				events.stream().limit(TABLE_ROWS).map(event -> List.of(
						PersonalDataExport.instant(event.getStartsAt()), PersonalDataExport.instant(event.getEndsAt()),
						event.getSummary() == null ? "—" : event.getSummary(),
						names.getOrDefault(event.getSubscriptionId(), "—"))).toList(),
				events.size() > TABLE_ROWS ? t(locale, "export.pdf.calendar.eventsCapped", TABLE_ROWS) : null));
		return tables;
	}

	private List<CalendarSubscription> subscriptionsOf(User user) {
		Query query = Query.query(Criteria.where("userId").is(user.getId())).with(Sort.by("createdAt", "_id"));
		query.fields().exclude("encryptedUrl", "etag", "lastModified");
		return mongo.find(query, CalendarSubscription.class);
	}

	private List<CalendarEvent> eventsOf(User user, int limit) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by("startsAt", "_id")).limit(limit), CalendarEvent.class);
	}

	private static Map<String, Object> subscription(CalendarSubscription subscription) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", subscription.getId());
		out.put("name", subscription.getName());
		out.put("host", subscription.getHostMasked());
		out.put("color", subscription.getColor());
		out.put("enabled", subscription.isEnabled());
		out.put("autoConvert", subscription.getAutoConvert());
		out.put("status", subscription.getLastStatus());
		out.put("lastFetchedAt", subscription.getLastFetchedAt());
		out.put("createdAt", subscription.getCreatedAt());
		return out;
	}

	private static Map<String, Object> event(CalendarEvent event) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("subscriptionId", event.getSubscriptionId());
		out.put("uid", event.getUid());
		out.put("recurrenceId", event.getRecurrenceId());
		out.put("startsAt", event.getStartsAt());
		out.put("endsAt", event.getEndsAt());
		out.put("allDay", event.isAllDay());
		out.put("summary", event.getSummary());
		out.put("location", event.getLocation());
		return out;
	}

	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}
}
