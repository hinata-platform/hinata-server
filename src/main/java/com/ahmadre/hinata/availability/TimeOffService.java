package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
	private final SettingsService settings;
	private final AuditService audit;
	private final Clock clock;

	/**
	 * What an operator's own absence types are, when there are any. Injected as a provider so this
	 * module runs with absence management absent, off, or not yet built — it asks the catalogue
	 * only about an id somebody sent, and gets "no such type" when there is nobody to ask.
	 */
	private final ObjectProvider<TimeOffCatalogue> catalogueProvider;

	/**
	 * Whether a type has to be asked for rather than simply entered, when anything above knows.
	 * Injected the same way and for the same reason as the catalogue: with absence management
	 * absent or off, everything may be entered directly, which is how this module always worked.
	 */
	private final ObjectProvider<TimeOffGate> gateProvider;

	private TimeOffCatalogue catalogue() {
		return catalogueProvider.getIfAvailable(TimeOffCatalogue::unknown);
	}

	private TimeOffGate gate() {
		return gateProvider.getIfAvailable(TimeOffGate::open);
	}

	public record Draft(String userId, TimeOff.Type type, String typeId, LocalDate from, LocalDate to,
			Boolean halfDay, String note) {
	}

	/** An edit; null leaves a field alone, and a blank note clears it. */
	public record Patch(TimeOff.Type type, String typeId, LocalDate from, LocalDate to, Boolean halfDay,
			String note) {
	}

	/** A page of absences and how much of them the reader may see. */
	public record Listing(Page<TimeOff> page, AvailabilityAccess.Sight sight) {
	}

	/** One person's absences touching [from] to [to], newest first. Filtered in the query. */
	public Listing page(User viewer, String userId, LocalDate from, LocalDate to, int page, int size) {
		AvailabilityAccess.Visible visible = access.requireVisible(viewer, userId);
		if (from != null && to != null) {
			CapacityService.assertWindow(from, to);
		}
		Criteria criteria = Criteria.where("userId").is(visible.userId());
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
				() -> mongo.count(Query.query(criteria), TimeOff.class)), visible.sight());
	}

	/**
	 * Enters an absence the direct way: somebody writes the days they are away and they are away.
	 *
	 * <p>Closed for a type somebody has to approve — see {@link TimeOffGate}. That road exists
	 * because this module predates approvals, and leaving it open would let a person walk past
	 * their own by using the older screen.
	 */
	public TimeOff create(User viewer, Draft draft) {
		User person = access.requireKeeper(viewer, draft.userId());
		gate().assertDirectEntry(draft.typeId(), person.getId(), viewer);
		return enter(viewer, person, draft);
	}

	/**
	 * Writes the absence, without asking whether this was the right road to it.
	 *
	 * <p>For a caller that has already settled the question its own way — today that is an
	 * approved request, which decided who may have these days before it got here, and whose
	 * decider is a lead rather than somebody who keeps absences. It still goes through every rule
	 * about the absence itself: the catalogue, the window, the half day, the note, how far from
	 * today it may sit and how many one year may hold.
	 */
	public TimeOff enter(User actor, User person, Draft draft) {
		TimeOff item = TimeOff.builder().userId(person.getId()).createdBy(actor.getId()).build();
		apply(item, draft.type(), draft.typeId(), draft.from(), draft.to(), draft.halfDay(), draft.note());
		assertNearToday(item, person);
		assertRoomIn(person.getId(), item.getFrom().getYear());
		TimeOff saved = timeOff.save(item);
		recordForOther(actor, person, "created", saved);
		return saved;
	}

	public TimeOff update(User viewer, String id, Patch patch) {
		TimeOff item = writable(viewer, id);
		User person = users.findById(item.getUserId()).orElse(null);
		// Only when the type changes, and for the type it changes to: retyping an absence into one
		// that needs approving is the same walk-around as entering it that way, while editing the
		// note on an absence that already needs none is nobody's business but the owner's.
		if (patch.typeId() != null && !patch.typeId().equals(item.getTypeId())) {
			gate().assertDirectEntry(patch.typeId(), item.getUserId(), viewer);
		}
		LocalDate fromBefore = item.getFrom();
		LocalDate toBefore = item.getTo();
		apply(item,
				patch.type() != null ? patch.type() : item.getType(),
				patch.typeId() != null ? patch.typeId() : item.getTypeId(),
				patch.from() != null ? patch.from() : item.getFrom(),
				patch.to() != null ? patch.to() : item.getTo(),
				patch.halfDay() != null ? patch.halfDay() : item.getHalfDay(),
				patch.note() != null ? patch.note() : item.getNote());
		// Checked when the days change, so an old absence keeps its note editable once it drifted out.
		if (!item.getFrom().equals(fromBefore) || !item.getTo().equals(toBefore)) {
			assertNearToday(item, person);
		}
		if (item.getFrom().getYear() != fromBefore.getYear()) {
			assertRoomIn(item.getUserId(), item.getFrom().getYear());
		}
		item.setUpdatedAt(clock.instant());
		TimeOff saved = timeOff.save(item);
		if (person != null) {
			recordForOther(viewer, person, "updated", saved);
		}
		return saved;
	}

	public void delete(User viewer, String id) {
		TimeOff item = writable(viewer, id);
		timeOff.delete(item);
		users.findById(item.getUserId()).ifPresent(person -> recordForOther(viewer, person, "deleted", item));
	}

	private void apply(TimeOff item, TimeOff.Type type, String typeId, LocalDate from, LocalDate to,
			Boolean halfDay, String note) {
		// An operator's own type decides the stored kind; without one the three values are the
		// whole answer, exactly as they were before absence management existed.
		TimeOff.Type stored = type;
		String cleanTypeId = typeId == null || typeId.isBlank() ? null : typeId.strip();
		if (cleanTypeId != null) {
			stored = catalogue().kindOf(cleanTypeId)
					.orElseThrow(() -> ApiException.badRequest("error.availability.timeOffTypeUnknown"));
		}
		if (stored == null || from == null || to == null) {
			throw ApiException.badRequest("error.availability.timeOffInvalid");
		}
		CapacityService.assertWindow(from, to);
		boolean half = Boolean.TRUE.equals(halfDay);
		if (half && !from.equals(to)) {
			throw ApiException.badRequest("error.availability.halfDaySingle");
		}
		String cleanNote = note == null || note.isBlank() ? null : note.strip();
		if (cleanNote != null && cleanNote.length() > TimeOff.NOTE_MAX) {
			throw ApiException.badRequest("error.availability.noteTooLong", TimeOff.NOTE_MAX);
		}
		item.setType(stored);
		item.setTypeId(cleanTypeId);
		item.setFrom(from);
		item.setTo(to);
		item.setHalfDay(half ? Boolean.TRUE : null);
		item.setNote(cleanNote);
	}

	/** Refuses one more absence in a year that already holds {@link TimeOff#PER_YEAR_MAX} of them. */
	private void assertRoomIn(String userId, int year) {
		Query inYear = Query.query(Criteria.where("userId").is(userId)
				.and("from").gte(LocalDate.of(year, 1, 1)).lte(LocalDate.of(year, 12, 31)));
		if (mongo.count(inYear, TimeOff.class) >= TimeOff.PER_YEAR_MAX) {
			throw ApiException.badRequest("error.availability.timeOffPerYear", TimeOff.PER_YEAR_MAX);
		}
	}

	/**
	 * Refuses an absence further than {@link TimeOff#YEARS_AROUND_TODAY} years from today, in the
	 * person's zone like every other day of theirs; the instance's for a person that is gone.
	 */
	private void assertNearToday(TimeOff item, User person) {
		LocalDate today = LocalDate.now(clock.withZone(UserZones.of(person, settings.get())));
		if (item.getFrom().isBefore(today.minusYears(TimeOff.YEARS_AROUND_TODAY))
				|| item.getTo().isAfter(today.plusYears(TimeOff.YEARS_AROUND_TODAY))) {
			throw ApiException.badRequest("error.availability.timeOffOutOfRange", TimeOff.YEARS_AROUND_TODAY);
		}
	}

	/** An absence the viewer may change: their own, or anybody's for somebody who keeps absences. */
	private TimeOff writable(User viewer, String id) {
		TimeOff item = timeOff.findById(id).orElseThrow(() -> ApiException.notFound("timeOff"));
		if (!access.keeps(viewer) && !viewer.getId().equals(item.getUserId())) {
			throw ApiException.notFound("timeOff");
		}
		return item;
	}

	/**
	 * What an administrator did to somebody's absence: the change and the days, never the type or
	 * the note. A sick day is health data, and the audit log keeps its entries long after the
	 * absence and the account are gone.
	 */
	private void recordForOther(User actor, User person, String change, TimeOff item) {
		if (actor.getId().equals(person.getId())) {
			return;
		}
		audit.event(AuditAction.AVAILABILITY_TIME_OFF_CHANGED).actor(actor).target(person)
				.meta("change", change)
				.meta("from", String.valueOf(item.getFrom()))
				.meta("to", String.valueOf(item.getTo()))
				.log();
	}
}
