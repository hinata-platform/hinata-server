package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.CapacityService;
import com.ahmadre.hinata.me.TimePreferences;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.notification.NotificationService.TargetPeriod;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reminds people of the targets they set themselves (HIN-92).
 *
 * <p>Only ever the person, about their own day or week. There is no team reminder, no list of
 * who was reminded and no audit record of a reminder: each of those would be an evaluation of
 * people (§ 87 Abs. 1 Nr. 6 BetrVG, R7). The target is the person's, in {@link TimePreferences};
 * the operator can only suggest one.
 *
 * <p>A reminder is due once the person's own clock has passed their reminder time on that day,
 * and a later run of the same day still sends it, so a restart or a busy instance delays a
 * reminder instead of dropping it. Its key is claimed before anything else is read
 * ({@link TimeMark}); only then does the run ask whether the day is a working day and
 * how much was recorded. A day that is not a working day, whatever the reason, is simply no
 * reminder day, and nothing says why.
 *
 * <p>Reads availability, so {@code ModuleBoundaryTest} names it as a reader, called only by
 * {@link TimeReminderJob}. It writes marks and notifications and never an entry (R9).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class TimeReminders {

	static final int BATCH = 500;

	/** How long one run may take before it leaves the rest to the next one. */
	static final Duration RUN_BUDGET = Duration.ofMinutes(4);

	/**
	 * What a reminder reads of a person: their clock, their targets and where to reach them, and the
	 * primitives the constructor Spring Data builds a {@code User} through refuses to take as null.
	 */
	private static final String[] PERSON_FIELDS = { "active", "timezone", "locale", "email", "timePreferences",
			"notificationPreferences", "emailVerified", "awaitingApproval", "totpEnabled" };

	private final MongoTemplate mongo;
	private final TimeTrackingSettings policy;
	private final SettingsService serverSettings;
	private final CapacityService capacity;
	private final TimeMarks marks;
	private final NotificationService notifications;
	private final Clock clock;

	/** One reminder whose time has come, over the days from [from] to [to]. */
	record Due(User person, TargetPeriod period, LocalDate from, LocalDate to, int targetMinutes) {

		String key() {
			return TimeMarks.reminderKey(person.getId(), period.name(), from);
		}
	}

	private record Span(LocalDate from, LocalDate to) {
	}

	/** Runs through everyone with a target, and returns how many reminders went out. */
	int run() {
		if (!policy.advancedEnabled() || !policy.targetRemindersEnabled()) {
			return 0;
		}
		Instant deadline = clock.instant().plus(RUN_BUDGET);
		ServerSettings server = serverSettings.get();
		DayOfWeek weekStartsOn = policy.approvalPeriod().weekStartsOn();
		int sent = 0;
		String after = null;
		while (true) {
			Criteria withTarget = Criteria.where("timePreferences.targetsSet").is(true);
			if (after != null) {
				withTarget = withTarget.and("_id").gt(after);
			}
			Query query = Query.query(withTarget).with(Sort.by("_id")).limit(BATCH);
			query.fields().include(PERSON_FIELDS);
			List<User> batch = mongo.find(query, User.class);
			if (batch.isEmpty()) {
				break;
			}
			sent += remind(batch, server, weekStartsOn);
			if (batch.size() < BATCH) {
				break;
			}
			if (clock.instant().isAfter(deadline)) {
				// The people after this batch are not lost: their reminders stay due for the
				// rest of the day, and the next run starts again from the first.
				log.warn("[time] reminder run stopped after {} to stay within its budget", RUN_BUDGET);
				break;
			}
			after = batch.getLast().getId();
		}
		return sent;
	}

	private int remind(List<User> people, ServerSettings server, DayOfWeek weekStartsOn) {
		List<Due> due = dueOf(people, server, weekStartsOn);
		if (due.isEmpty()) {
			return 0;
		}
		List<String> keys = due.stream().map(Due::key).toList();
		Set<String> taken = marks.existing(keys);
		Set<String> claimed = marks.claimAll(keys.stream().filter(key -> !taken.contains(key)).toList());
		List<Due> onWorkingDays = onWorkingDays(
				due.stream().filter(reminder -> claimed.contains(reminder.key())).toList());
		Map<String, Long> recorded = recordedMinutes(onWorkingDays);
		int sent = 0;
		for (Due reminder : onWorkingDays) {
			long minutes = recorded.getOrDefault(recordedKey(reminder), 0L);
			if (minutes >= reminder.targetMinutes()) {
				continue;
			}
			try {
				notifications.notifyTimeTargetReminder(reminder.person(), reminder.period(), (int) minutes,
						reminder.targetMinutes());
				sent++;
			}
			catch (RuntimeException ex) {
				// Without the person: a log line naming who was reminded is the list this class
				// exists not to keep.
				log.warn("[time] a target reminder could not be delivered: {}", ex.toString());
			}
		}
		return sent;
	}

	/** The reminders of [people] whose time has come today, in each person's own zone. */
	private List<Due> dueOf(List<User> people, ServerSettings server, DayOfWeek weekStartsOn) {
		Instant now = clock.instant();
		List<Due> due = new ArrayList<>();
		for (User person : people) {
			if (!person.isActive() || person.getTimePreferences() == null) {
				continue;
			}
			TimePreferences prefs = person.getTimePreferences().sanitized();
			ZonedDateTime local = now.atZone(UserZones.of(person, server));
			LocalDate today = local.toLocalDate();
			int minute = local.getHour() * 60 + local.getMinute();
			if (prefs.getDailyTargetMinutes() != null && minute >= prefs.getDailyReminderAt()) {
				due.add(new Due(person, TargetPeriod.DAY, today, today, prefs.getDailyTargetMinutes()));
			}
			if (prefs.getWeeklyTargetMinutes() != null && today.getDayOfWeek() == prefs.getWeeklyReminderDay()
					&& minute >= prefs.getWeeklyReminderAt()) {
				LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(weekStartsOn));
				due.add(new Due(person, TargetPeriod.WEEK, weekStart, today, prefs.getWeeklyTargetMinutes()));
			}
		}
		return due;
	}

	/**
	 * The reminders falling on a working day of their person. People in different zones can be on
	 * different days, so availability is asked once per day in the batch, not once per person.
	 */
	private List<Due> onWorkingDays(List<Due> reminders) {
		Set<String> working = new HashSet<>();
		reminders.stream()
				.collect(Collectors.groupingBy(Due::to, Collectors.mapping(r -> r.person().getId(), Collectors.toSet())))
				.forEach((day, ids) -> capacity.workingOn(ids, day).forEach(id -> working.add(id + '|' + day)));
		return reminders.stream().filter(r -> working.contains(r.person().getId() + '|' + r.to())).toList();
	}

	/** Minutes recorded per person and span: one aggregation per distinct span, over user_date. */
	private Map<String, Long> recordedMinutes(List<Due> reminders) {
		Map<String, Long> minutes = new HashMap<>();
		reminders.stream()
				.collect(Collectors.groupingBy(r -> new Span(r.from(), r.to()),
						Collectors.mapping(r -> r.person().getId(), Collectors.toSet())))
				.forEach((span, ids) -> mongo.aggregate(Aggregation.newAggregation(
								Aggregation.match(Criteria.where("userId").in(ids)
										.and("date").gte(span.from()).lte(span.to())),
								Aggregation.group("userId").sum("durationMinutes").as("minutes")),
						WorkItem.class, Document.class).forEach(row -> minutes.put(
								recordedKey(String.valueOf(row.get("_id")), span),
								((Number) row.get("minutes")).longValue())));
		return minutes;
	}

	private static String recordedKey(Due reminder) {
		return recordedKey(reminder.person().getId(), new Span(reminder.from(), reminder.to()));
	}

	private static String recordedKey(String userId, Span span) {
		return userId + '|' + span.from() + '|' + span.to();
	}
}
