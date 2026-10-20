package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one class of the module that writes audit records and sends notifications, and only after
 * the commit.
 *
 * <p>{@code ModuleBoundaryTest} refuses a call to the audit or notification service from any
 * other class of the module: a write that delivered from inside its transaction would have told
 * somebody about a change that was then rolled back, or told them twice after a retry.
 *
 * <p>Notifications (HIN-46) go to the people a change is about and never to whoever made it: a
 * planner's assignment to the person put on the shift (a sign-up of one's own tells nobody), a
 * planner taking somebody off to that person, a moved or cancelled event once to each person with
 * a place on it, a moved or deleted shift to its people, and a shift somebody left short to the
 * project's planners, at most once an hour per shift. {@code NotificationService.notifyEach} drops
 * everybody who cannot see the project before anything is written. The reminder job hands its due
 * assignments to {@link #remind}, outside any transaction.
 *
 * <p>HIN-162 adds the waiting list's three: moved up (with the deadline to confirm), passed over
 * because of another shift at that time, and the deadline missed. Each goes to that one person.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftDelivery {

	/** How often the planners of one shift hear that it was left short, at most. */
	static final Duration SHORT_NOTICE_EVERY = Duration.ofHours(1);

	private final AuditService audit;
	private final NotificationService notifications;
	private final ShiftNotices notices;
	private final ShiftAccess access;
	private final MongoTemplate mongo;
	private final Clock clock;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAudited(ShiftDomainEvents.Audited event) {
		audit(event);
	}

	/**
	 * Writes an audit record for something that wrote nothing, so no transaction waits to commit:
	 * a shift plan exported as a file (HIN-163). The writes of the module come through {@link #onAudited}.
	 */
	public void audit(ShiftDomainEvents.Audited event) {
		AuditService.Entry entry = audit.event(event.action())
				.actor(event.actorId(), event.actorLabel())
				.target(event.targetId(), null);
		event.meta().forEach(entry::meta);
		entry.log();
	}

	/** A planner put somebody on a shift; signing oneself up tells nobody. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAssigned(ShiftDomainEvents.Assigned event) {
		if (event.self() || event.userId().equals(event.actorId())) {
			return;
		}
		safely("assigned", () -> aboutShift(Notification.Type.SHIFT_ASSIGNED, ShiftNotices.Kind.ASSIGNED,
				event.shiftId(), List.of(event.userId()), ShiftLinks.event(event.eventId())));
	}

	/**
	 * A place was freed. Taken off by a planner: the person hears it. Withdrawn from, declined or let lapse
	 * by the person: see {@link #onWithdrawn}. Freed by the system (membership or account ended):
	 * nobody.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onReleased(ShiftDomainEvents.Released event) {
		if (event.actorId() == null || event.actorId().equals(event.userId())) {
			return;
		}
		safely("released", () -> aboutShift(Notification.Type.SHIFT_RELEASED, ShiftNotices.Kind.RELEASED,
				event.shiftId(), List.of(event.userId()), ShiftLinks.MY_SHIFTS));
	}

	/**
	 * The person gave their place up, declined it or let the deadline pass: the planners hear it
	 * when the shift is still short. On the follow-up thread ({@link ShiftFollowUps}), queued behind
	 * the waiting list moving up, so a place the list filled is not reported.
	 */
	@Async(ShiftFollowUps.EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onWithdrawn(ShiftDomainEvents.Released event) {
		if (event.actorId() == null || !event.actorId().equals(event.userId())) {
			return;
		}
		safely("short", () -> shortOfPeople(event));
	}

	/** An event moved or was cancelled: one notice per person with a place on it, however many. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onEventChanged(ShiftDomainEvents.EventChanged event) {
		safely("event", () -> {
			ShiftEvent shiftEvent = mongo.findById(event.eventId(), ShiftEvent.class);
			if (shiftEvent == null) {
				return;
			}
			Query holders = Query.query(Criteria.where("eventId").is(event.eventId())
					.and("status").in(ShiftAssignment.Status.ACTIVE));
			holders.fields().include("userId");
			Set<String> people = mongo.find(holders, ShiftAssignment.class).stream()
					.map(ShiftAssignment::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
			people.addAll(event.alsoTell());
			people.remove(event.actorId());
			ShiftNotices.Kind kind = event.cancelled() ? ShiftNotices.Kind.EVENT_CANCELLED
					: ShiftNotices.Kind.EVENT_MOVED;
			notifications.notifyEach(Notification.Type.SHIFT_EVENT_CHANGED, shiftEvent.getProjectId(), people,
					ShiftLinks.event(shiftEvent.getId()),
					reader -> notices.of(reader, kind, null, shiftEvent.getTitle(), shiftEvent.getStartsAt(),
							shiftEvent.getEndsAt(), shiftEvent.getTimezone()));
		});
	}

	/** One shift moved or was deleted: its people hear it, the planner who did it does not. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onShiftChanged(ShiftDomainEvents.ShiftChanged event) {
		safely("shift", () -> {
			Shift shift = event.shift();
			ShiftEvent shiftEvent = mongo.findById(shift.getEventId(), ShiftEvent.class);
			if (shiftEvent == null) {
				return;
			}
			Set<String> people = new LinkedHashSet<>(event.userIds());
			people.remove(event.actorId());
			ShiftNotices.Kind kind = event.deleted() ? ShiftNotices.Kind.SHIFT_DELETED : ShiftNotices.Kind.SHIFT_MOVED;
			notifications.notifyEach(Notification.Type.SHIFT_CHANGED, shift.getProjectId(), people,
					event.deleted() ? ShiftLinks.MY_SHIFTS : ShiftLinks.event(shiftEvent.getId()),
					reader -> notices.of(reader, kind, shift.getTitle(), shiftEvent.getTitle(), shift.getStartsAt(),
							shift.getEndsAt(), shiftEvent.getTimezone()));
		});
	}

	/** HIN-161: a series changed or ended; each person hears once how many of their places it touched. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onSeriesChanged(ShiftDomainEvents.SeriesChanged event) {
		safely("series", () -> {
			Map<String, Integer> counts = new LinkedHashMap<>(event.counts());
			counts.remove(event.actorId());
			notifications.notifyEach(Notification.Type.SHIFT_SERIES_CHANGED, event.projectId(), counts.keySet(),
					ShiftLinks.MY_SHIFTS, reader -> notices.seriesChanged(reader, counts.getOrDefault(reader.getId(), 1)));
		});
	}

	/**
	 * HIN-162: somebody moved up from the waiting list. Push: a place opened; bell and mail name the
	 * shift and the deadline to confirm it.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPromoted(ShiftDomainEvents.Promoted event) {
		safely("promoted", () -> {
			Shift shift = mongo.findById(event.shiftId(), Shift.class);
			ShiftEvent shiftEvent = shift == null ? null : mongo.findById(shift.getEventId(), ShiftEvent.class);
			if (shiftEvent == null) {
				return;
			}
			notifications.notifyEach(Notification.Type.SHIFT_PROMOTED, shift.getProjectId(), List.of(event.userId()),
					ShiftLinks.event(shiftEvent.getId()),
					reader -> notices.promoted(reader, shift.getTitle(), shiftEvent.getTitle(), shift.getStartsAt(),
							shift.getEndsAt(), shiftEvent.getTimezone(), event.confirmBy()));
		});
	}

	/** HIN-162: a place opened, but the person already holds another shift at that time. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onWaitlistSkipped(ShiftDomainEvents.WaitlistSkipped event) {
		safely("waitlistSkipped", () -> aboutShift(Notification.Type.SHIFT_WAITLIST_SKIPPED,
				ShiftNotices.Kind.WAITLIST_SKIPPED, event.shiftId(), List.of(event.userId()),
				ShiftLinks.event(event.eventId())));
	}

	/** HIN-162: the person did not confirm in time; the place went on. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onWaitlistExpired(ShiftDomainEvents.WaitlistExpired event) {
		safely("waitlistExpired", () -> aboutShift(Notification.Type.SHIFT_WAITLIST_EXPIRED,
				ShiftNotices.Kind.WAITLIST_EXPIRED, event.shiftId(), List.of(event.userId()), ShiftLinks.MY_SHIFTS));
	}

	/**
	 * Reminds the people of [due], assignments the reminder job has already marked as sent. One
	 * read for their shifts and one for their events, then one fan-out per project, with a batch
	 * per shift: who can see a project is asked once per run, not once per shift. A shift or event
	 * cancelled or deleted in the meantime reminds nobody.
	 */
	public void remind(Collection<ShiftAssignment> due) {
		if (due.isEmpty()) {
			return;
		}
		Map<String, List<String>> peopleByShift = new LinkedHashMap<>();
		for (ShiftAssignment assignment : due) {
			peopleByShift.computeIfAbsent(assignment.getShiftId(), id -> new java.util.ArrayList<>())
					.add(assignment.getUserId());
		}
		Map<String, Shift> shifts = byId(mongo.find(Query.query(Criteria.where("_id").in(peopleByShift.keySet())),
				Shift.class), Shift::getId);
		Set<String> eventIds = shifts.values().stream().map(Shift::getEventId).collect(Collectors.toSet());
		Map<String, ShiftEvent> events = byId(mongo.find(Query.query(Criteria.where("_id").in(eventIds)),
				ShiftEvent.class), ShiftEvent::getId);
		Map<String, List<NotificationService.NoticeBatch>> byProject = new LinkedHashMap<>();
		peopleByShift.forEach((shiftId, people) -> {
			Shift shift = shifts.get(shiftId);
			ShiftEvent event = shift == null ? null : events.get(shift.getEventId());
			if (shift == null || shift.isDeleted() || shift.isCancelled() || event == null || event.isCancelled()) {
				return;
			}
			byProject.computeIfAbsent(shift.getProjectId(), id -> new java.util.ArrayList<>())
					.add(new NotificationService.NoticeBatch(people, ShiftLinks.event(event.getId()),
							reader -> notices.of(reader, ShiftNotices.Kind.REMINDER, shift.getTitle(), event.getTitle(),
									shift.getStartsAt(), shift.getEndsAt(), event.getTimezone())));
		});
		byProject.forEach((projectId, batches) -> safely("reminder",
				() -> notifications.notifyEachInProject(Notification.Type.SHIFT_REMINDER, projectId, batches)));
	}

	/**
	 * Somebody withdrew from or declined their place. When the shift now has fewer people than it needs,
	 * still lies ahead and is neither cancelled nor deleted, its planners hear it, unless they
	 * heard about this shift within the hour: the stamp is set by a conditional update, so two
	 * people leaving at once tell the planners once.
	 */
	private void shortOfPeople(ShiftDomainEvents.Released event) {
		Instant now = clock.instant();
		Criteria due = Criteria.where("_id").is(event.shiftId()).and("endsAt").gt(now)
				.andOperator(ShiftRules.shortOfPeople(),
						new Criteria().orOperator(Criteria.where("lastShortNoticeAt").is(null),
								Criteria.where("lastShortNoticeAt").lte(now.minus(SHORT_NOTICE_EVERY))));
		Shift shift = mongo.findAndModify(Query.query(due), new Update().set("lastShortNoticeAt", now),
				FindAndModifyOptions.options().returnNew(true), Shift.class);
		if (shift == null) {
			return;
		}
		ShiftEvent shiftEvent = mongo.findById(shift.getEventId(), ShiftEvent.class);
		if (shiftEvent == null || shiftEvent.isCancelled()) {
			return;
		}
		Set<String> planners = new LinkedHashSet<>(access.plannerIds(shift.getProjectId()));
		planners.remove(event.userId());
		notifications.notifyEach(Notification.Type.SHIFT_HELPER_DECLINED, shift.getProjectId(), planners,
				ShiftLinks.event(shiftEvent.getId()),
				reader -> notices.of(reader, ShiftNotices.Kind.SHORT, shift.getTitle(), shiftEvent.getTitle(),
						shift.getStartsAt(), shift.getEndsAt(), shiftEvent.getTimezone(), shift.assigned(),
						shift.required()));
	}

	/** A notice about one shift to [people], worded from the shift and its event as they are now. */
	private void aboutShift(Notification.Type type, ShiftNotices.Kind kind, String shiftId, Collection<String> people,
			String link) {
		Shift shift = mongo.findById(shiftId, Shift.class);
		ShiftEvent shiftEvent = shift == null ? null : mongo.findById(shift.getEventId(), ShiftEvent.class);
		if (shiftEvent == null) {
			return;
		}
		notifications.notifyEach(type, shift.getProjectId(), people, link,
				reader -> notices.of(reader, kind, shift.getTitle(), shiftEvent.getTitle(), shift.getStartsAt(),
						shift.getEndsAt(), shiftEvent.getTimezone()));
	}

	private static <T> Map<String, T> byId(List<T> items, Function<T, String> id) {
		Map<String, T> map = new LinkedHashMap<>();
		items.forEach(item -> map.put(id.apply(item), item));
		return map;
	}

	/**
	 * A notice that fails is logged and dropped: the change it reports is committed, and a mail
	 * server that is down must not turn a successful request into an error.
	 */
	private static void safely(String what, Runnable delivery) {
		try {
			delivery.run();
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] {} notice failed: {}", what, failure.toString(), failure);
		}
	}
}
