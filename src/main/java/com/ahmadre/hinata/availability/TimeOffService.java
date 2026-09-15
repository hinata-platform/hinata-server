package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Absences: a person keeps their own, an administrator keeps anybody's, and a lead may read the
 * type and span of a member's while the policy allows it ({@link AvailabilityAccess}).
 *
 * <p>No approval workflow. Whether a vacation is granted is HR software's business; this records
 * that somebody is away, so that capacity is right.
 */
@Service
@RequiredArgsConstructor
public class TimeOffService {

	public static final int PAGE_MAX = 100;
	static final int PAGE_INDEX_MAX = 10_000;

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("from"), Sort.Order.desc("_id"));

	private final TimeOffRepository timeOff;
	private final MongoTemplate mongo;
	private final AvailabilityAccess access;
	private final UserRepository users;
	private final AuditService audit;
	private final Clock clock;

	public record Draft(String userId, TimeOff.Type type, LocalDate from, LocalDate to, Boolean halfDay,
			String note) {
	}

	/** An edit; null leaves a field alone, and a blank note clears it. */
	public record Patch(TimeOff.Type type, LocalDate from, LocalDate to, Boolean halfDay, String note) {
	}

	/** A page of absences and how much of them the reader may see. */
	public record Listing(Page<TimeOff> page, AvailabilityAccess.Sight sight) {
	}

	/** One person's absences touching [from] to [to], newest first. Filtered in the query. */
	public Listing page(User viewer, String userId, LocalDate from, LocalDate to, int page, int size) {
		String person = userId == null || userId.isBlank() ? viewer.getId() : userId;
		AvailabilityAccess.Sight sight = access.of(viewer, person);
		if (sight == AvailabilityAccess.Sight.NONE) {
			throw ApiException.forbidden("error.availability.forbidden");
		}
		if (from != null && to != null) {
			CapacityService.assertWindow(from, to);
		}
		Criteria criteria = Criteria.where("userId").is(person);
		if (from != null) {
			criteria.and("to").gte(from);
		}
		if (to != null) {
			criteria.and("from").lte(to);
		}
		PageRequest request = PageRequest.of(Math.clamp(page, 0, PAGE_INDEX_MAX), Math.clamp(size, 1, PAGE_MAX),
				NEWEST_FIRST);
		List<TimeOff> rows = mongo.find(Query.query(criteria).with(request), TimeOff.class);
		return new Listing(PageableExecutionUtils.getPage(rows, request,
				() -> mongo.count(Query.query(criteria), TimeOff.class)), sight);
	}

	public TimeOff create(User viewer, Draft draft) {
		User person = target(viewer, draft.userId());
		TimeOff item = TimeOff.builder().userId(person.getId()).createdBy(viewer.getId()).build();
		apply(item, draft.type(), draft.from(), draft.to(), draft.halfDay(), draft.note());
		TimeOff saved = timeOff.save(item);
		recordForOther(viewer, person, "created", saved);
		return saved;
	}

	public TimeOff update(User viewer, String id, Patch patch) {
		TimeOff item = writable(viewer, id);
		apply(item,
				patch.type() != null ? patch.type() : item.getType(),
				patch.from() != null ? patch.from() : item.getFrom(),
				patch.to() != null ? patch.to() : item.getTo(),
				patch.halfDay() != null ? patch.halfDay() : item.getHalfDay(),
				patch.note() != null ? patch.note() : item.getNote());
		item.setUpdatedAt(clock.instant());
		TimeOff saved = timeOff.save(item);
		users.findById(saved.getUserId()).ifPresent(person -> recordForOther(viewer, person, "updated", saved));
		return saved;
	}

	public void delete(User viewer, String id) {
		TimeOff item = writable(viewer, id);
		timeOff.delete(item);
		users.findById(item.getUserId()).ifPresent(person -> recordForOther(viewer, person, "deleted", item));
	}

	private void apply(TimeOff item, TimeOff.Type type, LocalDate from, LocalDate to, Boolean halfDay, String note) {
		if (type == null || from == null || to == null) {
			throw ApiException.badRequest("error.availability.timeOffInvalid");
		}
		CapacityService.assertWindow(from, to);
		if (ChronoUnit.DAYS.between(from, to) + 1 > TimeOff.DAYS_MAX) {
			throw ApiException.badRequest("error.availability.windowTooLong", TimeOff.DAYS_MAX);
		}
		boolean half = Boolean.TRUE.equals(halfDay);
		if (half && !from.equals(to)) {
			throw ApiException.badRequest("error.availability.halfDaySingle");
		}
		String cleanNote = note == null || note.isBlank() ? null : note.strip();
		if (cleanNote != null && cleanNote.length() > TimeOff.NOTE_MAX) {
			throw ApiException.badRequest("error.availability.noteTooLong", TimeOff.NOTE_MAX);
		}
		item.setType(type);
		item.setFrom(from);
		item.setTo(to);
		item.setHalfDay(half ? Boolean.TRUE : null);
		item.setNote(cleanNote);
	}

	/** An absence the viewer may change: their own, or anybody's for an administrator. */
	private TimeOff writable(User viewer, String id) {
		TimeOff item = timeOff.findById(id).orElseThrow(() -> ApiException.notFound("timeOff"));
		if (!viewer.isAdmin() && !viewer.getId().equals(item.getUserId())) {
			throw ApiException.notFound("timeOff");
		}
		return item;
	}

	private User target(User viewer, String userId) {
		if (userId == null || userId.isBlank() || userId.equals(viewer.getId())) {
			return viewer;
		}
		if (!viewer.isAdmin()) {
			throw ApiException.forbidden("error.availability.forbidden");
		}
		return users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
	}

	private void recordForOther(User actor, User person, String change, TimeOff item) {
		if (actor.getId().equals(person.getId())) {
			return;
		}
		audit.event(AuditAction.AVAILABILITY_TIME_OFF_CHANGED).actor(actor).target(person)
				.meta("change", change)
				.meta("type", String.valueOf(item.getType()))
				.meta("from", String.valueOf(item.getFrom()))
				.meta("to", String.valueOf(item.getTo()))
				.log();
	}
}
