package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creating, changing, moving, cancelling and deleting events and shifts (HIN-43, rules 1 to 13).
 *
 * <p>Every write that touches more than one document runs in one transaction through
 * {@link ShiftTx}: a moved event never leaves its shifts or assignments behind, and a deleted
 * shift never leaves an assignment that points nowhere. Audit records are published as
 * {@link ShiftDomainEvents.Audited} and written by {@link ShiftDelivery} after the commit.
 */
@Service
@RequiredArgsConstructor
public class ShiftService {

	private final MongoTemplate mongo;
	private final ShiftTx tx;
	private final ShiftAccess access;
	private final ShiftSettings settings;
	private final SettingsService serverSettings;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	/** A new event. Description and location optional; the zone defaults to the creator's. */
	public record EventDraft(String title, String description, String location, Instant startsAt,
			Instant endsAt, String timezone, Boolean selfAssignable) {
	}

	/**
	 * A change to an event; null leaves a field as it is, a blank description or location clears it.
	 * {@code startsAt} alone moves the event with its duration; with {@code endsAt} it sets both.
	 */
	public record EventPatch(String title, String description, String location, Instant startsAt,
			Instant endsAt, String timezone, Boolean selfAssignable) {
	}

	/** A new shift. Without a count it needs one person and has no upper limit. */
	public record ShiftDraft(String title, Instant startsAt, Instant endsAt, Integer requiredHelpers,
			Integer maxHelpers) {
	}

	/** A change to a shift; null leaves a field, {@code clearMaxHelpers} removes the upper limit. */
	public record ShiftPatch(String title, Instant startsAt, Instant endsAt, Integer requiredHelpers,
			Integer maxHelpers, Boolean clearMaxHelpers) {
	}

	/**
	 * The same shift on several days: the template's wall-clock times in the event's zone, put on
	 * each day of [days]. A template that ends on the day after it starts does so on every day.
	 */
	public record BatchDraft(ShiftDraft template, List<LocalDate> days) {
	}

	/**
	 * Somebody whose places now overlap after a move: their place on [shiftId] (of the moved event)
	 * and the place it collides with, named only when the caller may see that project.
	 */
	public record Warning(String userId, String shiftId, String conflictingShiftId) {
	}

	/** What a change produced, and who now collides. */
	public record Change<T>(T item, List<Warning> warnings) {
	}

	// --- events ---------------------------------------------------------------

	public ShiftEvent createEvent(User caller, String projectId, EventDraft draft) {
		settings.requireEnabled();
		Project project = access.plannedProject(projectId, caller);
		Instant start = ShiftRules.stored(draft.startsAt());
		Instant end = ShiftRules.stored(draft.endsAt());
		ShiftRules.eventSpan(start, end);
		String zone = draft.timezone() == null || draft.timezone().isBlank()
				? UserZones.of(caller, serverSettings.get()).getId()
				: UserZones.normalize(draft.timezone());
		ShiftEvent event = ShiftEvent.builder()
				.projectId(project.getId())
				.title(ShiftRules.title(draft.title(), true))
				.description(ShiftRules.description(draft.description()))
				.location(ShiftRules.location(draft.location()))
				.startsAt(start).endsAt(end).timezone(zone)
				.selfAssignable(draft.selfAssignable() == null || draft.selfAssignable())
				.cancelled(false)
				.createdBy(caller.getId())
				.build();
		return tx.run(() -> {
			ShiftEvent saved = mongo.insert(event);
			audited(AuditAction.SHIFT_EVENT_CREATED, caller, saved.getId(), Map.of("projectId", project.getId()));
			return saved;
		});
	}

	public Change<ShiftEvent> updateEvent(User caller, String eventId, EventPatch patch) {
		settings.requireEnabled();
		ShiftEvent event = access.plannedEvent(eventId, caller).item();
		Instant oldStart = event.getStartsAt();
		Instant newStart = patch.startsAt() != null ? ShiftRules.stored(patch.startsAt()) : oldStart;
		long delta = Duration.between(oldStart, newStart).toMillis();
		Instant newEnd = patch.endsAt() != null ? ShiftRules.stored(patch.endsAt())
				: event.getEndsAt().plusMillis(delta);
		ShiftRules.eventSpan(newStart, newEnd);
		for (Shift shift : liveShifts(eventId)) {
			Instant start = shift.getStartsAt().plusMillis(delta);
			Instant end = shift.getEndsAt().plusMillis(delta);
			if (start.isBefore(newStart) || end.isAfter(newEnd)) {
				throw ApiException.badRequest("error.shifts.shiftOutsideEvent");
			}
		}
		Update update = new Update().set("startsAt", newStart).set("endsAt", newEnd)
				.set("updatedAt", clock.instant());
		List<String> changed = new ArrayList<>();
		if (patch.title() != null) {
			update.set("title", ShiftRules.title(patch.title(), true));
			changed.add("title");
		}
		if (patch.description() != null) {
			setOrUnset(update, "description", ShiftRules.description(patch.description()));
			changed.add("description");
		}
		if (patch.location() != null) {
			setOrUnset(update, "location", ShiftRules.location(patch.location()));
			changed.add("location");
		}
		if (patch.timezone() != null) {
			// A new zone moves no instant: the client converts wall-clock times itself (HIN-160).
			String zone = UserZones.normalize(patch.timezone());
			update.set("timezone", zone == null ? event.getTimezone() : zone);
			changed.add("timezone");
		}
		if (patch.selfAssignable() != null) {
			update.set("selfAssignable", patch.selfAssignable());
			changed.add("selfAssignable");
		}
		if (delta != 0) {
			changed.add("moved");
		}
		if (!newEnd.equals(event.getEndsAt().plusMillis(delta))) {
			changed.add("resized");
		}
		ShiftEvent updated = tx.run(() -> {
			ShiftEvent saved = mongo.findAndModify(Query.query(Criteria.where("_id").is(eventId)), update,
					FindAndModifyOptions.options().returnNew(true), ShiftEvent.class);
			if (saved == null) {
				throw ApiException.notFound("shiftEvent");
			}
			if (delta != 0) {
				mongo.updateMulti(Query.query(Criteria.where("eventId").is(eventId)), moveBy(delta, null, true),
						Shift.class);
				mongo.updateMulti(Query.query(Criteria.where("eventId").is(eventId)),
						moveBy(delta, clock.instant().plus(ShiftRules.SHIFT_MAX), false), ShiftAssignment.class);
			}
			if (delta != 0 && !saved.isCancelled()) {
				events.publishEvent(new ShiftDomainEvents.EventChanged(eventId, saved.getProjectId(), caller.getId(),
						false));
			}
			audited(AuditAction.SHIFT_EVENT_UPDATED, caller, eventId, Map.of("projectId", saved.getProjectId(),
					"changed", String.join(",", changed), "movedByMinutes", String.valueOf(delta / 60_000)));
			return saved;
		});
		List<Warning> warnings = delta == 0 ? List.of()
				: warnings(caller, Criteria.where("eventId").is(eventId));
		return new Change<>(updated, warnings);
	}

	/**
	 * Rule 10: the event, its shifts and its assignments are marked cancelled together. Assignments
	 * stay, so the people on them can be told; sign-up closes, and a cancelled place blocks no
	 * other shift, is never reminded of and is never anybody's next shift.
	 */
	public ShiftEvent cancelEvent(User caller, String eventId) {
		settings.requireEnabled();
		ShiftEvent event = access.plannedEvent(eventId, caller).item();
		return tx.run(() -> {
			ShiftEvent saved = mongo.findAndModify(Query.query(Criteria.where("_id").is(eventId)),
					new Update().set("cancelled", true).set("updatedAt", clock.instant()),
					FindAndModifyOptions.options().returnNew(true), ShiftEvent.class);
			if (saved == null) {
				throw ApiException.notFound("shiftEvent");
			}
			long shifts = mongo.updateMulti(Query.query(Criteria.where("eventId").is(eventId)),
					new Update().set("cancelled", true), Shift.class).getMatchedCount();
			long assignments = mongo.updateMulti(Query.query(Criteria.where("eventId").is(eventId)),
					new Update().set("cancelled", true), ShiftAssignment.class).getMatchedCount();
			if (!event.isCancelled()) {
				events.publishEvent(new ShiftDomainEvents.EventChanged(eventId, saved.getProjectId(), caller.getId(),
						true));
			}
			audited(AuditAction.SHIFT_EVENT_CANCELLED, caller, eventId, Map.of("projectId", saved.getProjectId(),
					"shifts", String.valueOf(shifts), "assignments", String.valueOf(assignments)));
			return saved;
		});
	}

	/**
	 * Rule 12: an event goes only when it is cancelled or nobody holds a place on it. Each shift is
	 * tombstoned first and its assignments deleted before it, all in one transaction, so a sign-up
	 * racing the deletion either lands before it and blocks it or finds no place to take.
	 */
	public void deleteEvent(User caller, String eventId) {
		settings.requireEnabled();
		access.plannedEvent(eventId, caller);
		tx.run(() -> {
			ShiftEvent event = mongo.findById(eventId, ShiftEvent.class);
			if (event == null) {
				throw ApiException.notFound("shiftEvent");
			}
			Query ofEvent = Query.query(Criteria.where("eventId").is(eventId));
			long shifts = mongo.updateMulti(ofEvent, new Update().set("deleted", true), Shift.class).getMatchedCount();
			if (!event.isCancelled() && mongo.exists(Query.query(Criteria.where("eventId").is(eventId)
					.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class)) {
				throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.eventHasAssignments");
			}
			long assignments = mongo.remove(ofEvent, ShiftAssignment.class).getDeletedCount();
			mongo.remove(ofEvent, Shift.class);
			mongo.remove(Query.query(Criteria.where("_id").is(eventId)), ShiftEvent.class);
			audited(AuditAction.SHIFT_EVENT_DELETED, caller, eventId, Map.of("projectId", event.getProjectId(),
					"shifts", String.valueOf(shifts), "assignmentsRemoved", String.valueOf(assignments)));
		});
	}

	// --- shifts ---------------------------------------------------------------

	public Shift createShift(User caller, String eventId, ShiftDraft draft) {
		settings.requireEnabled();
		ShiftEvent event = access.plannedEvent(eventId, caller).item();
		Shift shift = newShift(event, draft, ShiftRules.stored(draft.startsAt()), ShiftRules.stored(draft.endsAt()));
		return tx.run(() -> {
			assertRoomFor(eventId, 1);
			Shift saved = mongo.insert(shift);
			audited(AuditAction.SHIFT_CREATED, caller, saved.getId(), Map.of("eventId", eventId,
					"projectId", event.getProjectId()));
			return saved;
		});
	}

	/**
	 * The same shift on several days, all or none. Every day is checked before anything is written;
	 * a day that fails is named in the refusal's details with the reason ({@code details} maps the
	 * ISO date to the message key).
	 */
	public List<Shift> createShifts(User caller, String eventId, BatchDraft batch) {
		settings.requireEnabled();
		ShiftEvent event = access.plannedEvent(eventId, caller).item();
		if (batch == null || batch.template() == null || batch.days() == null || batch.days().isEmpty()) {
			throw ApiException.badRequest("error.shifts.batchEmpty");
		}
		Set<LocalDate> days = new LinkedHashSet<>(batch.days());
		days.remove(null);
		if (days.isEmpty() || days.size() > ShiftRules.BATCH_DAYS_MAX) {
			throw ApiException.badRequest("error.shifts.batchEmpty");
		}
		ShiftDraft template = batch.template();
		ShiftRules.span(template.startsAt(), template.endsAt());
		ZoneId zone = ShiftRules.zoneOf(event.getTimezone());
		LocalDateTime startLocal = LocalDateTime.ofInstant(template.startsAt(), zone);
		LocalDateTime endLocal = LocalDateTime.ofInstant(template.endsAt(), zone);
		long dayOffset = ChronoUnit.DAYS.between(startLocal.toLocalDate(), endLocal.toLocalDate());
		List<Shift> shifts = new ArrayList<>();
		Map<String, String> failures = new LinkedHashMap<>();
		for (LocalDate day : days) {
			Instant start = ZonedDateTime.ofLocal(day.atTime(startLocal.toLocalTime()), zone, null).toInstant();
			Instant end = ZonedDateTime.ofLocal(day.plusDays(dayOffset).atTime(endLocal.toLocalTime()), zone, null)
					.toInstant();
			try {
				shifts.add(newShift(event, template, ShiftRules.stored(start), ShiftRules.stored(end)));
			}
			catch (ApiException refused) {
				failures.put(day.toString(), refused.getMessageKey());
			}
		}
		if (!failures.isEmpty()) {
			throw ShiftRules.refusal(HttpStatus.BAD_REQUEST, "error.shifts.batchInvalid", failures);
		}
		return tx.run(() -> {
			assertRoomFor(eventId, shifts.size());
			List<Shift> saved = new ArrayList<>(mongo.insertAll(shifts));
			saved.sort((a, b) -> a.getStartsAt().compareTo(b.getStartsAt()));
			saved.forEach(shift -> audited(AuditAction.SHIFT_CREATED, caller, shift.getId(),
					Map.of("eventId", eventId, "projectId", event.getProjectId(),
							"batch", String.valueOf(saved.size()))));
			return saved;
		});
	}

	public Change<Shift> updateShift(User caller, String shiftId, ShiftPatch patch) {
		settings.requireEnabled();
		Shift shift = access.plannedShift(shiftId, caller).item();
		ShiftEvent event = mongo.findById(shift.getEventId(), ShiftEvent.class);
		if (event == null) {
			throw ApiException.notFound("shift");
		}
		Instant start = patch.startsAt() != null ? ShiftRules.stored(patch.startsAt()) : shift.getStartsAt();
		Instant end = patch.endsAt() != null ? ShiftRules.stored(patch.endsAt()) : shift.getEndsAt();
		boolean moved = !start.equals(shift.getStartsAt()) || !end.equals(shift.getEndsAt());
		if (moved) {
			ShiftRules.shiftSpan(start, end, event);
		}
		boolean clearMax = Boolean.TRUE.equals(patch.clearMaxHelpers());
		Integer max = clearMax ? null : patch.maxHelpers() != null ? patch.maxHelpers() : shift.getMaxHelpers();
		int required = patch.requiredHelpers() != null ? patch.requiredHelpers() : shift.required();
		ShiftRules.helpers(required, max);

		Criteria live = Criteria.where("_id").is(shiftId).and("deleted").is(false);
		// Rule 5: a lower limit is written only while it still holds everybody already on the shift,
		// decided by the database in the write itself, so a sign-up in between cannot slip past it.
		Criteria match = max != null && !max.equals(shift.getMaxHelpers())
				? live.and("assignedCount").lte(max)
				: live;
		Update update = new Update().set("startsAt", start).set("endsAt", end).set("requiredHelpers", required)
				.set("updatedAt", clock.instant());
		setOrUnset(update, "maxHelpers", max);
		List<String> changed = new ArrayList<>();
		if (patch.title() != null) {
			setOrUnset(update, "title", ShiftRules.title(patch.title(), false));
			changed.add("title");
		}
		if (moved) {
			changed.add("moved");
		}
		if (patch.requiredHelpers() != null) {
			changed.add("requiredHelpers");
		}
		if (patch.maxHelpers() != null || clearMax) {
			changed.add("maxHelpers");
		}
		Shift updated = tx.run(() -> {
			Shift saved = mongo.findAndModify(Query.query(match), update,
					FindAndModifyOptions.options().returnNew(true), Shift.class);
			if (saved == null) {
				Shift now = mongo.findById(shiftId, Shift.class);
				if (now == null || now.isDeleted()) {
					throw ApiException.notFound("shift");
				}
				throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.maxBelowAssigned",
						Map.of("assigned", String.valueOf(now.assigned())));
			}
			if (moved) {
				Update follow = new Update().set("startsAt", start).set("endsAt", end);
				if (start.isAfter(clock.instant().plus(ShiftRules.SHIFT_MAX))) {
					follow.unset("reminderSentAt");
				}
				mongo.updateMulti(Query.query(Criteria.where("shiftId").is(shiftId)), follow, ShiftAssignment.class);
				if (!saved.isCancelled()) {
					events.publishEvent(new ShiftDomainEvents.ShiftChanged(saved, caller.getId(), false,
							holders(shiftId)));
				}
			}
			audited(AuditAction.SHIFT_UPDATED, caller, shiftId, Map.of("eventId", saved.getEventId(),
					"projectId", saved.getProjectId(), "changed", String.join(",", changed)));
			return saved;
		});
		List<Warning> warnings = moved ? warnings(caller, Criteria.where("shiftId").is(shiftId)) : List.of();
		return new Change<>(updated, warnings);
	}

	/**
	 * Rule 11: tombstone, then the assignments, then the shift, in one transaction. A sign-up that
	 * committed first is deleted with the shift; one that comes later finds the tombstone.
	 */
	public void deleteShift(User caller, String shiftId) {
		settings.requireEnabled();
		access.plannedShift(shiftId, caller);
		tx.run(() -> {
			Shift tombstoned = mongo.findAndModify(Query.query(Criteria.where("_id").is(shiftId)),
					new Update().set("deleted", true).set("updatedAt", clock.instant()),
					FindAndModifyOptions.options().returnNew(true), Shift.class);
			if (tombstoned == null) {
				throw ApiException.notFound("shift");
			}
			Query ofShift = Query.query(Criteria.where("shiftId").is(shiftId));
			long active = mongo.count(Query.query(Criteria.where("shiftId").is(shiftId)
					.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class);
			if (active > 0 && !tombstoned.isCancelled()) {
				events.publishEvent(new ShiftDomainEvents.ShiftChanged(tombstoned, caller.getId(), true,
						holders(shiftId)));
			}
			long removed = mongo.remove(ofShift, ShiftAssignment.class).getDeletedCount();
			mongo.remove(Query.query(Criteria.where("_id").is(shiftId)), Shift.class);
			audited(AuditAction.SHIFT_DELETED, caller, shiftId, Map.of("eventId", tombstoned.getEventId(),
					"projectId", tombstoned.getProjectId(), "assignmentsRemoved", String.valueOf(removed),
					"activeRemoved", String.valueOf(active)));
		});
	}

	// --- helpers --------------------------------------------------------------

	private Shift newShift(ShiftEvent event, ShiftDraft draft, Instant start, Instant end) {
		ShiftRules.shiftSpan(start, end, event);
		int required = draft.requiredHelpers() == null ? 1 : draft.requiredHelpers();
		ShiftRules.helpers(required, draft.maxHelpers());
		return Shift.builder()
				.eventId(event.getId())
				.projectId(event.getProjectId())
				.title(ShiftRules.title(draft.title(), false))
				.startsAt(start).endsAt(end)
				.requiredHelpers(required)
				.maxHelpers(draft.maxHelpers())
				.assignedCount(0)
				.cancelled(event.isCancelled())
				.deleted(false)
				.build();
	}

	/** Rule 7: at most {@link ShiftRules#SHIFTS_PER_EVENT_MAX} live shifts per event. */
	private void assertRoomFor(String eventId, int adding) {
		long existing = mongo.count(Query.query(Criteria.where("eventId").is(eventId).and("deleted").is(false)),
				Shift.class);
		if (existing + adding > ShiftRules.SHIFTS_PER_EVENT_MAX) {
			throw ApiException.badRequest("error.shifts.tooManyShifts", ShiftRules.SHIFTS_PER_EVENT_MAX);
		}
	}

	/** Who holds a place on the shift right now, over the shift_status index, ids only. */
	private List<String> holders(String shiftId) {
		Query query = Query.query(Criteria.where("shiftId").is(shiftId).and("status").in(ShiftAssignment.Status.ACTIVE));
		query.fields().include("userId");
		return mongo.find(query, ShiftAssignment.class).stream().map(ShiftAssignment::getUserId).distinct().toList();
	}

	private List<Shift> liveShifts(String eventId) {
		Query query = Query.query(Criteria.where("eventId").is(eventId).and("deleted").is(false));
		query.fields().include("startsAt", "endsAt");
		return mongo.find(query, Shift.class);
	}

	/**
	 * Moves the times of every matched document by [deltaMillis] in the database, in one pipeline
	 * update. For assignments [reminderAfter] is given: a place that now starts after it is owed a
	 * new reminder, so its sent mark is removed.
	 */
	static AggregationUpdate moveBy(long deltaMillis, Instant reminderAfter, boolean touch) {
		Document set = new Document("startsAt", new Document("$add", List.of("$startsAt", deltaMillis)))
				.append("endsAt", new Document("$add", List.of("$endsAt", deltaMillis)));
		if (reminderAfter != null) {
			set.append("reminderSentAt", new Document("$cond", List.of(
					new Document("$gt", List.of(new Document("$add", List.of("$startsAt", deltaMillis)),
							Date.from(reminderAfter))),
					"$$REMOVE", "$reminderSentAt")));
		}
		if (touch) {
			set.append("updatedAt", "$$NOW");
		}
		AggregationOperation stage = context -> new Document("$set", set);
		return AggregationUpdate.from(List.of(stage));
	}

	/**
	 * Who now holds two overlapping places, after a move of the assignments [moved] selects. One
	 * read over the user_start index for everybody affected, bounded below by the longest shift.
	 */
	private List<Warning> warnings(User caller, Criteria moved) {
		List<ShiftAssignment> mine = mongo.find(Query.query(moved.and("status").in(ShiftAssignment.Status.ACTIVE)
				.and("cancelled").is(false)), ShiftAssignment.class);
		if (mine.isEmpty()) {
			return List.of();
		}
		Set<String> people = new LinkedHashSet<>();
		Instant from = null;
		Instant to = null;
		for (ShiftAssignment assignment : mine) {
			people.add(assignment.getUserId());
			from = from == null || assignment.getStartsAt().isBefore(from) ? assignment.getStartsAt() : from;
			to = to == null || assignment.getEndsAt().isAfter(to) ? assignment.getEndsAt() : to;
		}
		List<ShiftAssignment> held = mongo.find(Query.query(Criteria.where("userId").in(people)
				.and("startsAt").lt(to).gt(from.minus(ShiftRules.SHIFT_MAX))
				.and("status").in(ShiftAssignment.Status.ACTIVE).and("cancelled").is(false)), ShiftAssignment.class);
		List<ShiftConflicts.Collision> collisions = ShiftConflicts.collisions(mine, held);
		if (collisions.isEmpty()) {
			return List.of();
		}
		Set<String> visible = access.visibleProjectIds(caller);
		return collisions.stream()
				.map(collision -> new Warning(collision.userId(), collision.shiftId(),
						visible.contains(collision.otherProjectId()) ? collision.otherShiftId() : null))
				.toList();
	}

	private void audited(AuditAction action, User actor, String targetId, Map<String, String> meta) {
		String label = actor.getDisplayName() != null ? actor.getDisplayName() : actor.getEmail();
		events.publishEvent(new ShiftDomainEvents.Audited(action, actor.getId(), label, targetId, meta));
	}

	private static void setOrUnset(Update update, String field, Object value) {
		if (value == null) {
			update.unset(field);
		}
		else {
			update.set(field, value);
		}
	}
}
