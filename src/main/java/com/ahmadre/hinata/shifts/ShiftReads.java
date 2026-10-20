package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The module's read models and their wire shapes (HIN-43).
 *
 * <p>Every read costs a fixed number of queries however many rows it returns: the visible projects
 * once, the rows, then one query per kind of thing the rows point at ({@code $in}), never one per
 * row. Lists carry numbers and no people; a person's name appears once, in the {@code users} map
 * of a detail view, however many shifts they are on. Wall-clock times come computed in the event's
 * zone ({@code startsLocal}, {@code endsLocal}), so the app needs no zone database.
 */
@Service
@RequiredArgsConstructor
public class ShiftReads {

	/** Most assignee ids one shift lists; {@code assignedCount} says how many there are. */
	public static final int ASSIGNEES_MAX = 50;

	/** Most shifts the calendar returns; beyond it {@code truncated} is set. */
	public static final int CALENDAR_MAX = 2000;

	/** HIN-166: events of the projects a planner plans that come with the calendar, at most. */
	static final int PLANNABLE_EVENTS_MAX = 200;

	/** What {@link EventHead#from} reads of an event, and so all a read for heads only fetches. */
	private static final String[] HEAD_FIELDS = { "projectId", "title", "location", "timezone", "selfAssignable",
			"cancelled", "seriesId", "occurrenceDate", "detached", "startsAt", "endsAt" };

	/** A cap on the assignments one detail view reads, far above any real event. */
	static final int DETAIL_ASSIGNMENTS_MAX = 20_000;

	private final MongoTemplate mongo;
	private final ShiftAccess access;
	private final ShiftSettings settings;
	private final Clock clock;
	private final ShiftSeriesLabels labels;
	private final SettingsService serverSettings;

	// --- wire shapes ----------------------------------------------------------

	/** A person as a detail view names them. */
	public record UserCard(String name, String avatarUrl) {
	}

	/**
	 * One shift. {@code assigneeIds} is filled in detail views only (at most
	 * {@link #ASSIGNEES_MAX}, in order of sign-up) and empty in lists, which carry the count.
	 * {@code myAssignment} is the caller's own active place on the shift, so the app can confirm,
	 * decline or give it up without another read; null when the caller holds none, and then
	 * {@code mine} is false.
	 *
	 * <p>HIN-162: {@code waitingCount} is how many wait for the shift, for everybody who sees it;
	 * {@code myWaitPosition} the caller's own place on that list (1 is next) and
	 * {@code myWaitAssignmentId} the entry to leave it with, both null when the caller does not
	 * wait; {@code waitingIds} names the people waiting, in order, only to a planner and only in
	 * detail views, and is empty everywhere else. Nobody waiting is ever in {@code assigneeIds}.
	 */
	public record ShiftResponse(String id, String eventId, String projectId, String title,
			Instant startsAt, Instant endsAt, String startsLocal, String endsLocal,
			int requiredHelpers, Integer maxHelpers, int assignedCount,
			boolean understaffed, boolean full, boolean mine, MyAssignment myAssignment, boolean cancelled,
			List<String> assigneeIds, int waitingCount, Integer myWaitPosition, String myWaitAssignmentId,
			List<String> waitingIds) {

		static ShiftResponse from(Shift shift, String zone, MyAssignment mine, List<String> assigneeIds,
				WaitView wait) {
			WaitView waiting = wait == null ? WaitView.NONE : wait;
			return new ShiftResponse(shift.getId(), shift.getEventId(), shift.getProjectId(), shift.getTitle(),
					shift.getStartsAt(), shift.getEndsAt(), ShiftRules.local(shift.getStartsAt(), zone),
					ShiftRules.local(shift.getEndsAt(), zone), shift.required(), shift.getMaxHelpers(),
					shift.assigned(), shift.understaffed(), shift.full(), mine != null, mine == null ? null : mine.in(zone),
					shift.isCancelled(),
					assigneeIds == null ? List.of() : assigneeIds, waiting.count(), waiting.myPosition(),
					waiting.myAssignmentId(), waiting.ids());
		}
	}

	/** HIN-162: one entry of a waiting list, in order. */
	record Waiter(String assignmentId, String userId) {
	}

	/** HIN-162: a waiting list as one caller may see it. */
	record WaitView(int count, Integer myPosition, String myAssignmentId, List<String> ids) {

		static final WaitView NONE = new WaitView(0, null, null, List.of());

		/** [queue] in order; [named] only for a planner's detail view. */
		static WaitView of(List<Waiter> queue, String callerId, boolean named) {
			if (queue == null || queue.isEmpty()) {
				return NONE;
			}
			Integer position = null;
			String entry = null;
			for (int i = 0; i < queue.size(); i++) {
				if (queue.get(i).userId().equals(callerId)) {
					position = i + 1;
					entry = queue.get(i).assignmentId();
					break;
				}
			}
			return new WaitView(queue.size(), position, entry,
					named ? queue.stream().map(Waiter::userId).toList() : List.of());
		}
	}

	/**
	 * The caller's own place on a shift: the assignment to act on and where it stands. {@code confirmBy}
	 * is set while a place taken from the waiting list waits for the person's yes, with
	 * {@code confirmByLocal} the same moment on the event's wall clock, so a sheet opened from anywhere
	 * shows the deadline; both null otherwise.
	 */
	public record MyAssignment(String id, ShiftAssignment.Status status, Instant confirmBy, String confirmByLocal) {

		static MyAssignment of(ShiftAssignment assignment) {
			return assignment == null ? null
					: new MyAssignment(assignment.getId(), assignment.getStatus(), assignment.getConfirmBy(), null);
		}

		/** This place with its deadline on the wall clock of [zone]. */
		MyAssignment in(String zone) {
			return confirmBy == null ? this : new MyAssignment(id, status, confirmBy, ShiftRules.local(confirmBy, zone));
		}
	}

	/**
	 * What a row that points at an event needs to show it. HIN-161: {@code seriesId} and
	 * {@code recurrenceLabel} (the series' rule in the reader's words) for an occurrence of a series,
	 * {@code detached} when it was changed on its own; null, null and false otherwise.
	 */
	/**
	 * An event as a shift row needs it. HIN-166: {@code projectId} and its span (instants and the
	 * wall clock of its zone), so the calendar can tell which event a span drawn by a planner falls
	 * into without asking again.
	 */
	public record EventHead(String id, String title, String location, String timezone, boolean selfAssignable,
			boolean cancelled, String seriesId, String recurrenceLabel, boolean detached, String projectId,
			Instant startsAt, Instant endsAt, String startsLocal, String endsLocal) {

		static EventHead from(ShiftEvent event, Map<String, String> labels) {
			return new EventHead(event.getId(), event.getTitle(), event.getLocation(), event.getTimezone(),
					event.isSelfAssignable(), event.isCancelled(), event.getSeriesId(),
					labelOf(event, labels), event.isDetached(), event.getProjectId(), event.getStartsAt(),
					event.getEndsAt(), ShiftRules.local(event.getStartsAt(), event.getTimezone()),
					ShiftRules.local(event.getEndsAt(), event.getTimezone()));
		}
	}

	private static String labelOf(ShiftEvent event, Map<String, String> labels) {
		return event.getSeriesId() == null ? null : labels.get(event.getSeriesId());
	}

	/**
	 * An event in a list. {@code placesRequired} adds up what the live shifts need,
	 * {@code placesTaken} the places taken towards that (a shift over its need counts as met, not
	 * as more), {@code shiftsUnderstaffed} the shifts still short, {@code mineCount} the caller's
	 * own places. {@code canPlan}: the caller plans the project (lead, team admin or planner).
	 */
	public record EventSummary(String id, String projectId, String title, String description, String location,
			Instant startsAt, Instant endsAt, String startsLocal, String endsLocal, String timezone,
			boolean cancelled, boolean selfAssignable, int placesRequired, int placesTaken,
			int shiftsUnderstaffed, int mineCount, boolean canPlan, String seriesId, String recurrenceLabel,
			boolean detached) {
	}

	/** An event with its shifts, and the people on them named once in {@code users}. */
	public record EventResponse(String id, String projectId, String title, String description, String location,
			Instant startsAt, Instant endsAt, String startsLocal, String endsLocal, String timezone,
			boolean cancelled, boolean selfAssignable, int placesRequired, int placesTaken,
			int shiftsUnderstaffed, int mineCount, boolean canPlan, String seriesId, String recurrenceLabel,
			boolean detached, List<ShiftResponse> shifts, Map<String, UserCard> users) {
	}

	/**
	 * One shift with its event and the people on it. {@code assignments} is there for a planner
	 * only: the active places in the order of {@code shift.assigneeIds}, each with the id to take
	 * the person off with; anybody else gets no such field.
	 */
	public record ShiftDetail(ShiftResponse shift, EventHead event, Map<String, UserCard> users, boolean canPlan,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<PlaceRow> assignments) {
	}

	/** A planner's handle on one active place: whose it is, how far it got, and its id. */
	public record PlaceRow(String id, String userId, ShiftAssignment.Status status) {

		static PlaceRow from(ShiftAssignment assignment) {
			return new PlaceRow(assignment.getId(), assignment.getUserId(), assignment.getStatus());
		}
	}

	/**
	 * Shifts in a window, their events once each, whether the cap cut the list, and which of the
	 * projects read the caller plans (HIN-45), so the calendar offers planning controls per project.
	 * HIN-166: {@code eventsTruncated} when the open events of the planned projects that come along
	 * stopped at {@link #PLANNABLE_EVENTS_MAX}, so the calendar asks for a drawn span's events itself.
	 */
	public record CalendarResponse(List<ShiftResponse> shifts, Map<String, EventHead> events, boolean truncated,
			Set<String> plannableProjectIds, boolean eventsTruncated) {
	}

	/**
	 * One day of the month view: the shifts starting that day in the caller's zone, counted.
	 * {@code mine} the caller's own places, {@code open} shifts open for sign-up with a free place,
	 * {@code understaffed} shifts still short of people. Cancelled shifts are never open or short.
	 */
	public record DayMarks(String date, int mine, int open, int understaffed) {
	}

	/** The days of a window that have anything to mark, in order; days with nothing are left out. */
	/** HIN-166: {@code plannableProjectIds} as on the calendar, so the month can offer "+" alone. */
	public record CalendarMarks(List<DayMarks> days, Set<String> plannableProjectIds) {
	}

	/** One person's place on a shift, as a write answers it. */
	public record AssignmentResponse(String id, String shiftId, String userId, ShiftAssignment.Status status,
			String assignedBy, Instant createdAt) {

		static AssignmentResponse from(ShiftAssignment assignment) {
			return new AssignmentResponse(assignment.getId(), assignment.getShiftId(), assignment.getUserId(),
					assignment.getStatus(), assignment.getAssignedBy(), assignment.getCreatedAt());
		}
	}

	/**
	 * What assigning, signing up, declining and withdrawing answer: the shift as it is now (with its
	 * people), the assignment that changed and hints for whoever asked ({@code absent} only when the
	 * caller may see absences, never a reason).
	 */
	public record AssignmentChange(ShiftResponse shift, AssignmentResponse assignment, List<String> warnings) {
	}

	/**
	 * One of the caller's own places, or (HIN-162, {@code /me/shifts/waiting}) one of their waiting
	 * entries. {@code confirmBy} is set while a place taken from the waiting list waits for the
	 * person's yes, with {@code confirmByLocal} the same moment on the event's wall clock; null
	 * otherwise.
	 */
	public record MyShiftResponse(String assignmentId, ShiftAssignment.Status status, String assignedBy,
			ShiftResponse shift, EventHead event, Instant confirmBy, String confirmByLocal) {
	}

	/** A changed event and who now collides because of it. */
	public record EventChange(EventResponse event, List<ShiftService.Warning> warnings) {
	}

	/** A changed shift and who now collides because of it. */
	public record ShiftChange(ShiftResponse shift, List<ShiftService.Warning> warnings) {
	}

	// --- events ---------------------------------------------------------------

	/** Events of every visible project, or of one, touching the window. */
	public Page<EventSummary> events(User caller, Instant from, Instant to, String projectId, int page, int size) {
		return events(caller, from, to, projectId, false, page, size);
	}

	/**
	 * As {@link #events(User, Instant, Instant, String, int, int)}; with [understaffedOnly] only the
	 * events with at least one live shift still short of people (HIN-164, "where are people
	 * missing"), filtered in the query so the pages stay whole.
	 */
	Page<EventSummary> events(User caller, Instant from, Instant to, String projectId, boolean understaffedOnly,
			int page, int size) {
		settings.requireEnabled();
		ShiftRules.window(from, to, ShiftRules.WINDOW_MAX);
		Set<String> projects = scope(caller, projectId);
		return understaffedOnly ? shortEventPage(caller, projects, from, to, page, size)
				: eventPage(caller, projects, from, to, page, size);
	}

	/** Events of one project; 403 for somebody who is not a member. */
	public Page<EventSummary> projectEvents(User caller, String projectId, Instant from, Instant to, int page,
			int size) {
		settings.requireEnabled();
		ShiftRules.window(from, to, ShiftRules.WINDOW_MAX);
		Project project = access.memberProject(projectId, caller);
		return eventPage(caller, Set.of(project.getId()), from, to, page, size);
	}

	public EventResponse event(User caller, String eventId) {
		settings.requireEnabled();
		ShiftAccess.Scoped<ShiftEvent> scoped = access.visibleEvent(eventId, caller);
		return detail(caller, scoped.item(), scoped.project());
	}

	/** The detail of an event the caller was just allowed to write. */
	EventResponse detail(User caller, ShiftEvent event, Project project) {
		List<Shift> shifts = mongo.find(Query.query(Criteria.where("eventId").is(event.getId()).and("deleted").is(false))
				.with(Sort.by("startsAt", "_id")), Shift.class);
		// Over event_status, and only what the rows show: whose place on which shift, and the
		// caller's own place with its deadline.
		Query places = Query.query(Criteria.where("eventId").is(event.getId())
				.and("status").in(ShiftAssignment.Status.ACTIVE))
				.with(Sort.by("createdAt", "_id")).limit(DETAIL_ASSIGNMENTS_MAX);
		places.fields().include("shiftId", "userId", "status", "createdAt", "confirmBy");
		List<ShiftAssignment> active = mongo.find(places, ShiftAssignment.class);
		Map<String, List<String>> byShift = new HashMap<>();
		Map<String, MyAssignment> mine = new HashMap<>();
		for (ShiftAssignment assignment : active) {
			List<String> ids = byShift.computeIfAbsent(assignment.getShiftId(), key -> new ArrayList<>());
			if (ids.size() < ASSIGNEES_MAX) {
				ids.add(assignment.getUserId());
			}
			if (assignment.getUserId().equals(caller.getId())) {
				mine.put(assignment.getShiftId(), MyAssignment.of(assignment));
			}
		}
		boolean canPlan = access.canPlan(project, caller);
		Map<String, List<Waiter>> waiting = waitlists(shifts.stream().map(Shift::getId).toList());
		List<ShiftResponse> rows = shifts.stream()
				.map(shift -> ShiftResponse.from(shift, event.getTimezone(), mine.get(shift.getId()),
						byShift.getOrDefault(shift.getId(), List.of()),
						WaitView.of(waiting.get(shift.getId()), caller.getId(), canPlan)))
				.toList();
		Totals totals = Totals.of(shifts);
		Set<String> people = byShift.values().stream().flatMap(List::stream)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		rows.forEach(row -> people.addAll(row.waitingIds()));
		return new EventResponse(event.getId(), event.getProjectId(), event.getTitle(), event.getDescription(),
				event.getLocation(), event.getStartsAt(), event.getEndsAt(),
				ShiftRules.local(event.getStartsAt(), event.getTimezone()),
				ShiftRules.local(event.getEndsAt(), event.getTimezone()), event.getTimezone(), event.isCancelled(),
				event.isSelfAssignable(), totals.required(), totals.taken(), totals.understaffed(), mine.size(),
				canPlan, event.getSeriesId(),
				labelOf(event, labels.of(caller, List.of(event))), event.isDetached(), rows, users(people));
	}

	// --- shifts ---------------------------------------------------------------

	public ShiftDetail shift(User caller, String shiftId) {
		settings.requireEnabled();
		ShiftAccess.Scoped<Shift> scoped = access.visibleShift(shiftId, caller);
		return shiftDetail(caller, scoped.item(), scoped.project());
	}

	ShiftDetail shiftDetail(User caller, Shift shift, Project project) {
		ShiftEvent event = mongo.findById(shift.getEventId(), ShiftEvent.class);
		if (event == null) {
			throw ApiException.notFound("shift");
		}
		List<ShiftAssignment> active = mongo.find(Query.query(Criteria.where("shiftId").is(shift.getId())
				.and("status").in(ShiftAssignment.Status.ACTIVE))
				.with(Sort.by("createdAt", "_id")).limit(DETAIL_ASSIGNMENTS_MAX), ShiftAssignment.class);
		MyAssignment mine = MyAssignment.of(active.stream()
				.filter(assignment -> assignment.getUserId().equals(caller.getId())).findFirst().orElse(null));
		List<String> ids = active.stream().map(ShiftAssignment::getUserId).limit(ASSIGNEES_MAX).toList();
		boolean canPlan = access.canPlan(project, caller);
		WaitView wait = WaitView.of(waitlists(List.of(shift.getId())).get(shift.getId()), caller.getId(), canPlan);
		Set<String> people = new LinkedHashSet<>(ids);
		people.addAll(wait.ids());
		List<PlaceRow> places = canPlan
				? active.stream().limit(ASSIGNEES_MAX).map(PlaceRow::from).toList()
				: null;
		return new ShiftDetail(ShiftResponse.from(shift, event.getTimezone(), mine, ids, wait),
				EventHead.from(event, labels.of(caller, List.of(event))),
				users(people), canPlan, places);
	}

	/**
	 * Shifts in a window across the visible projects (or one), at most [limit], with their events.
	 * [understaffed] keeps the shifts still short of people; [selfAssignable] keeps the shifts of
	 * events open (true) or closed (false) for sign-up.
	 */
	public CalendarResponse calendar(User caller, Instant from, Instant to, String projectId, Boolean understaffed,
			Boolean selfAssignable, Integer limit) {
		settings.requireEnabled();
		ShiftRules.window(from, to, ShiftRules.WINDOW_MAX);
		int cap = limit == null ? CALENDAR_MAX : Math.max(1, Math.min(limit, CALENDAR_MAX));
		Set<String> projects = scope(caller, projectId);
		if (projects.isEmpty()) {
			return new CalendarResponse(List.of(), Map.of(), false, Set.of(), false);
		}
		Set<String> plannable = access.plannableProjectIds(caller, projects);
		Criteria criteria = Criteria.where("projectId").in(projects)
				.and("startsAt").gt(from.minus(ShiftRules.SHIFT_MAX)).lt(to)
				.and("endsAt").gt(from)
				.and("deleted").is(false);
		if (selfAssignable != null) {
			Set<String> eventIds = eventIdsBySignUp(projects, from, to, selfAssignable);
			if (eventIds.isEmpty()) {
				return new CalendarResponse(List.of(), Map.of(), false, plannable, false);
			}
			criteria = criteria.and("eventId").in(eventIds);
		}
		if (Boolean.TRUE.equals(understaffed)) {
			criteria = new Criteria().andOperator(criteria, ShiftRules.shortOfPeople());
		}
		List<Shift> found = mongo.find(Query.query(criteria).with(Sort.by("startsAt", "_id")).limit(cap + 1),
				Shift.class);
		boolean truncated = found.size() > cap;
		List<Shift> shifts = truncated ? found.subList(0, cap) : found;
		Map<String, ShiftEvent> events = eventsById(shifts.stream().map(Shift::getEventId).collect(Collectors.toSet()));
		Map<String, MyAssignment> mine = myAssignments(caller, from, to);
		Map<String, List<Waiter>> waiting = waitlists(shifts.stream().map(Shift::getId).toList());
		List<ShiftResponse> rows = shifts.stream()
				.filter(shift -> events.containsKey(shift.getEventId()))
				.map(shift -> ShiftResponse.from(shift, events.get(shift.getEventId()).getTimezone(),
						mine.get(shift.getId()), List.of(), WaitView.of(waiting.get(shift.getId()), caller.getId(), false)))
				.toList();
		// HIN-166: a planner may draw a shift into an event that has none yet in this window, so
		// the events of the projects they plan come along, open ones only and none the shifts
		// already brought. One read over project_start, bounded below by the longest an event can
		// last, of the fields a head shows and never an event's description.
		Map<String, ShiftEvent> all = new LinkedHashMap<>(events);
		boolean eventsTruncated = false;
		if (!plannable.isEmpty()) {
			Query open = Query.query(Criteria.where("projectId").in(plannable)
					.and("startsAt").gt(from.minus(ShiftRules.EVENT_MAX)).lt(to)
					.and("endsAt").gt(from)
					.and("cancelled").ne(true)
					.and("_id").nin(events.keySet()))
					.with(Sort.by("startsAt", "_id")).limit(PLANNABLE_EVENTS_MAX + 1);
			open.fields().include(HEAD_FIELDS);
			List<ShiftEvent> openEvents = mongo.find(open, ShiftEvent.class);
			eventsTruncated = openEvents.size() > PLANNABLE_EVENTS_MAX;
			(eventsTruncated ? openEvents.subList(0, PLANNABLE_EVENTS_MAX) : openEvents)
					.forEach(event -> all.putIfAbsent(event.getId(), event));
		}
		Map<String, EventHead> heads = new LinkedHashMap<>();
		Map<String, String> seriesLabels = labels.of(caller, all.values());
		all.values().forEach(event -> heads.put(event.getId(), EventHead.from(event, seriesLabels)));
		return new CalendarResponse(rows, heads, truncated, plannable, eventsTruncated);
	}

	/**
	 * The month view's dots without the shifts behind them: per day (the shift's start in the
	 * caller's zone) how many places are the caller's, how many shifts are open for sign-up with a
	 * free place, and how many are short of people. Same rights and window as {@link #calendar}; one
	 * aggregation over {@code project_start}, after the ids of the caller's places and of the events
	 * open for sign-up. No names, no shift rows.
	 */
	public CalendarMarks marks(User caller, Instant from, Instant to, String projectId) {
		settings.requireEnabled();
		ShiftRules.window(from, to, ShiftRules.WINDOW_MAX);
		Set<String> projects = scope(caller, projectId);
		if (projects.isEmpty()) {
			return new CalendarMarks(List.of(), Set.of());
		}
		ZoneId zone = UserZones.of(caller, serverSettings.get());
		List<String> mine = new ArrayList<>(myAssignments(caller, from, to).keySet());
		List<String> openEvents = new ArrayList<>(eventIdsBySignUp(projects, from, to, true));
		Document hasRoom = new Document("$or", List.of(
				new Document("$eq", Arrays.asList(new Document("$ifNull",
						Arrays.asList("$maxHelpers", null)), null)),
				new Document("$lt", List.of("$assignedCount", "$maxHelpers"))));
		Document open = new Document("$and", List.of(new Document("$ne", List.of("$cancelled", true)),
				new Document("$in", List.of("$eventId", openEvents)), hasRoom));
		AggregationOperation group = context -> new Document("$group", new Document("_id",
				new Document("$dateToString", new Document("format", "%Y-%m-%d").append("date", "$startsAt")
						.append("timezone", zone.getId())))
				.append("mine", count(new Document("$in", List.of(new Document("$toString", "$_id"), mine))))
				.append("open", count(open))
				.append("understaffed", count(ShiftRules.shortOfPeopleExpression())));
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("projectId").in(projects).and("startsAt").gte(from).lt(to)
						.and("deleted").is(false)),
				group,
				Aggregation.match(new Criteria().orOperator(Criteria.where("mine").gt(0), Criteria.where("open").gt(0),
						Criteria.where("understaffed").gt(0))),
				Aggregation.sort(Sort.by("_id")));
		List<DayMarks> days = new ArrayList<>();
		for (Document row : mongo.aggregate(aggregation, mongo.getCollectionName(Shift.class), Document.class)) {
			days.add(new DayMarks(row.getString("_id"), number(row, "mine"), number(row, "open"),
					number(row, "understaffed")));
		}
		return new CalendarMarks(days, access.plannableProjectIds(caller, projects));
	}

	private static Document count(Document condition) {
		return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
	}

	// --- the caller's own -----------------------------------------------------

	/**
	 * The caller's own active places touching the window, in projects they can still see: leaving a
	 * project or losing a team grant takes its shifts off this list at once.
	 */
	public Page<MyShiftResponse> mine(User caller, Instant from, Instant to, int page, int size) {
		settings.requireEnabled();
		Instant start = from != null ? from : clock.instant();
		Instant end = to != null ? to : start.plus(ShiftRules.WINDOW_MAX);
		ShiftRules.window(start, end, ShiftRules.OWN_WINDOW_MAX);
		Set<String> projects = access.visibleProjectIds(caller);
		Pageable pageable = PageRequest.of(Math.max(page, 0), ShiftRules.pageSize(size), Sort.by("startsAt", "_id"));
		if (projects.isEmpty()) {
			return Page.empty(pageable);
		}
		Criteria criteria = Criteria.where("userId").is(caller.getId())
				.and("startsAt").gt(start.minus(ShiftRules.SHIFT_MAX)).lt(end)
				.and("endsAt").gt(start)
				.and("status").in(ShiftAssignment.Status.ACTIVE)
				.and("projectId").in(projects);
		List<ShiftAssignment> rows = mongo.find(Query.query(criteria).with(pageable), ShiftAssignment.class);
		List<MyShiftResponse> content = own(caller, rows);
		return PageableExecutionUtils.getPage(content, pageable,
				() -> mongo.count(Query.query(criteria), ShiftAssignment.class));
	}

	/** The caller's next place that is not over yet and not cancelled, or empty. */
	public Optional<MyShiftResponse> next(User caller) {
		settings.requireEnabled();
		Instant now = clock.instant();
		Set<String> projects = access.visibleProjectIds(caller);
		if (projects.isEmpty()) {
			return Optional.empty();
		}
		Criteria criteria = Criteria.where("userId").is(caller.getId())
				.and("startsAt").gt(now.minus(ShiftRules.SHIFT_MAX))
				.and("endsAt").gt(now)
				.and("status").in(ShiftAssignment.Status.ACTIVE)
				.and("cancelled").is(false)
				.and("projectId").in(projects);
		ShiftAssignment first = mongo.findOne(Query.query(criteria).with(Sort.by("startsAt", "_id")),
				ShiftAssignment.class);
		return first == null ? Optional.empty() : own(caller, List.of(first)).stream().findFirst();
	}

	/**
	 * HIN-162: the caller's own waiting entries for shifts that have not ended, in projects they
	 * still see, soonest first, at most {@link ShiftWaitlist#WAITING_MAX}. Each carries its place
	 * on the list in {@code shift.myWaitPosition}.
	 */
	public List<MyShiftResponse> waiting(User caller) {
		settings.requireEnabled();
		Instant now = clock.instant();
		Set<String> projects = access.visibleProjectIds(caller);
		if (projects.isEmpty()) {
			return List.of();
		}
		Criteria criteria = Criteria.where("userId").is(caller.getId())
				.and("startsAt").gt(now.minus(ShiftRules.SHIFT_MAX))
				.and("endsAt").gt(now)
				.and("status").is(ShiftAssignment.Status.WAITING)
				.and("projectId").in(projects);
		List<ShiftAssignment> rows = mongo.find(Query.query(criteria).with(Sort.by("startsAt", "_id"))
				.limit(ShiftWaitlist.WAITING_MAX), ShiftAssignment.class);
		return own(caller, rows);
	}

	/** One of the caller's own places as "my shifts" shows it, after a confirmation. */
	MyShiftResponse myShift(User caller, ShiftAssignment assignment) {
		return own(caller, List.of(assignment)).stream().findFirst()
				.orElseThrow(() -> ApiException.notFound("shiftAssignment"));
	}

	/** The answer to an assignment write: the shift with its people, the assignment, the hints. */
	AssignmentChange change(User caller, ShiftAssignment assignment, List<String> warnings) {
		ShiftAccess.Scoped<Shift> scoped = access.visibleShift(assignment.getShiftId(), caller);
		return new AssignmentChange(shiftDetail(caller, scoped.item(), scoped.project()).shift(),
				AssignmentResponse.from(assignment), warnings == null ? List.of() : warnings);
	}

	private List<MyShiftResponse> own(User caller, List<ShiftAssignment> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<String, Shift> shifts = byId(rows.stream().map(ShiftAssignment::getShiftId).collect(Collectors.toSet()),
				Shift.class, Shift::getId);
		Map<String, ShiftEvent> events = eventsById(rows.stream().map(ShiftAssignment::getEventId)
				.collect(Collectors.toSet()));
		Map<String, String> seriesLabels = labels.of(caller, events.values());
		Map<String, List<Waiter>> waiting = waitlists(shifts.keySet());
		List<MyShiftResponse> out = new ArrayList<>();
		for (ShiftAssignment row : rows) {
			Shift shift = shifts.get(row.getShiftId());
			ShiftEvent event = events.get(row.getEventId());
			if (shift == null || event == null || shift.isDeleted()) {
				continue;
			}
			MyAssignment own = row.active() ? MyAssignment.of(row) : null;
			out.add(new MyShiftResponse(row.getId(), row.getStatus(), row.getAssignedBy(),
					ShiftResponse.from(shift, event.getTimezone(), own, List.of(),
							WaitView.of(waiting.get(shift.getId()), caller.getId(), false)),
					EventHead.from(event, seriesLabels), row.getConfirmBy(),
					row.getConfirmBy() == null ? null : ShiftRules.local(row.getConfirmBy(), event.getTimezone())));
		}
		return out;
	}

	// --- helpers --------------------------------------------------------------

	/** The projects a list reads: all visible ones, or the one named, which then has to be visible. */
	private Set<String> scope(User caller, String projectId) {
		Set<String> visible = access.visibleProjectIds(caller);
		if (projectId == null || projectId.isBlank()) {
			return visible;
		}
		if (!visible.contains(projectId)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		return Set.of(projectId);
	}

	/**
	 * One page of events touching the window. An event starts at most fourteen days before it ends,
	 * so "ends after [from]" is read as "starts after [from] minus fourteen days" for the index and
	 * then checked exactly.
	 */
	private Page<EventSummary> eventPage(User caller, Set<String> projects, Instant from, Instant to, int page,
			int size) {
		Pageable pageable = PageRequest.of(Math.max(page, 0), ShiftRules.pageSize(size), Sort.by("startsAt", "_id"));
		if (projects.isEmpty()) {
			return Page.empty(pageable);
		}
		Criteria criteria = eventWindow(projects, from, to);
		List<ShiftEvent> events = mongo.find(Query.query(criteria).with(pageable), ShiftEvent.class);
		List<EventSummary> content = summaries(caller, events);
		return PageableExecutionUtils.getPage(content, pageable,
				() -> mongo.count(Query.query(criteria), ShiftEvent.class));
	}

	private static Criteria eventWindow(Set<String> projects, Instant from, Instant to) {
		return Criteria.where("projectId").in(projects)
				.and("startsAt").gt(from.minus(ShiftRules.EVENT_MAX)).lt(to)
				.and("endsAt").gt(from);
	}

	/**
	 * One page of the events touching the window that have a live shift short of people
	 * ({@link ShiftRules#shortOfPeople()}), decided inside the event query: the events of the
	 * window over {@code project_start}, each asking its shifts over {@code event_start} for one
	 * that is short ({@code $limit 1}), then the page and the total in one {@code $facet}. The cost
	 * follows the events in the window, never every shift of every visible project.
	 */
	private Page<EventSummary> shortEventPage(User caller, Set<String> projects, Instant from, Instant to, int page,
			int size) {
		Pageable pageable = PageRequest.of(Math.max(page, 0), ShiftRules.pageSize(size), Sort.by("startsAt", "_id"));
		if (projects.isEmpty()) {
			return Page.empty(pageable);
		}
		Document shortShift = new Document("$lookup", new Document("from", mongo.getCollectionName(Shift.class))
				.append("let", new Document("event", new Document("$toString", "$_id")))
				.append("pipeline", List.of(
						new Document("$match", new Document("$expr", new Document("$eq", List.of("$eventId", "$$event")))
								.append("deleted", false)),
						new Document("$match", new Document("$expr", ShiftRules.shortOfPeopleExpression())),
						new Document("$limit", 1),
						new Document("$project", new Document("_id", 1))))
				.append("as", "short"));
		Document facet = new Document("$facet", new Document("rows", List.of(
						new Document("$skip", pageable.getOffset()), new Document("$limit", pageable.getPageSize())))
				.append("total", List.of(new Document("$count", "n"))));
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(eventWindow(projects, from, to)),
				context -> shortShift,
				Aggregation.match(Criteria.where("short").ne(List.of())),
				Aggregation.sort(Sort.by("startsAt", "_id")),
				context -> facet);
		Document result = mongo.aggregate(aggregation, mongo.getCollectionName(ShiftEvent.class), Document.class)
				.getUniqueMappedResult();
		if (result == null) {
			return Page.empty(pageable);
		}
		List<ShiftEvent> events = new ArrayList<>();
		for (Object row : result.getList("rows", Object.class)) {
			Document document = (Document) row;
			document.remove("short");
			events.add(mongo.getConverter().read(ShiftEvent.class, document));
		}
		List<Object> total = result.getList("total", Object.class);
		long count = total.isEmpty() ? 0 : ((Number) ((Document) total.getFirst()).get("n")).longValue();
		return new PageImpl<>(summaries(caller, events), pageable, count);
	}

	private List<EventSummary> summaries(User caller, List<ShiftEvent> events) {
		if (events.isEmpty()) {
			return List.of();
		}
		List<String> ids = events.stream().map(ShiftEvent::getId).toList();
		Map<String, Totals> totals = totalsByEvent(ids);
		Map<String, Integer> mine = mineByEvent(caller, events);
		Set<String> plannable = access.plannableProjectIds(caller,
				events.stream().map(ShiftEvent::getProjectId).collect(Collectors.toSet()));
		Map<String, String> seriesLabels = labels.of(caller, events);
		return events.stream().map(event -> {
			Totals total = totals.getOrDefault(event.getId(), Totals.NONE);
			return new EventSummary(event.getId(), event.getProjectId(), event.getTitle(), event.getDescription(),
					event.getLocation(), event.getStartsAt(), event.getEndsAt(),
					ShiftRules.local(event.getStartsAt(), event.getTimezone()),
					ShiftRules.local(event.getEndsAt(), event.getTimezone()), event.getTimezone(),
					event.isCancelled(), event.isSelfAssignable(), total.required(), total.taken(),
					total.understaffed(), mine.getOrDefault(event.getId(), 0),
					plannable.contains(event.getProjectId()), event.getSeriesId(), labelOf(event, seriesLabels),
					event.isDetached());
		}).toList();
	}

	/** The numbers of a set of events, from the shifts' counters in one aggregation. */
	private Map<String, Totals> totalsByEvent(List<String> eventIds) {
		AggregationOperation group = context -> new Document("$group", new Document("_id", "$eventId")
				.append("required", new Document("$sum", "$requiredHelpers"))
				.append("taken", new Document("$sum", ShiftRules.placesTakenExpression()))
				.append("understaffed", new Document("$sum", new Document("$cond", List.of(
						ShiftRules.shortOfPeopleExpression(), 1, 0)))));
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("eventId").in(eventIds).and("deleted").is(false)), group);
		Map<String, Totals> out = new HashMap<>();
		for (Document row : mongo.aggregate(aggregation, mongo.getCollectionName(Shift.class), Document.class)) {
			out.put(row.getString("_id"), new Totals(number(row, "required"), number(row, "taken"),
					number(row, "understaffed")));
		}
		return out;
	}

	/** The caller's own places per event, in one read over the user_start index. */
	private Map<String, Integer> mineByEvent(User caller, List<ShiftEvent> events) {
		Instant from = events.stream().map(ShiftEvent::getStartsAt).min(Instant::compareTo).orElseThrow();
		Instant to = events.stream().map(ShiftEvent::getEndsAt).max(Instant::compareTo).orElseThrow();
		Query query = Query.query(Criteria.where("userId").is(caller.getId())
				.and("startsAt").gte(from).lt(to)
				.and("eventId").in(events.stream().map(ShiftEvent::getId).toList())
				.and("status").in(ShiftAssignment.Status.ACTIVE));
		query.fields().include("eventId");
		Map<String, Integer> out = new HashMap<>();
		for (ShiftAssignment row : mongo.find(query, ShiftAssignment.class)) {
			out.merge(row.getEventId(), 1, Integer::sum);
		}
		return out;
	}

	/** The caller's active places in the window, by shift: one read, id and status along. */
	private Map<String, MyAssignment> myAssignments(User caller, Instant from, Instant to) {
		Query query = Query.query(Criteria.where("userId").is(caller.getId())
				.and("startsAt").gt(from.minus(ShiftRules.SHIFT_MAX)).lt(to)
				.and("status").in(ShiftAssignment.Status.ACTIVE));
		query.fields().include("shiftId", "status");
		Map<String, MyAssignment> mine = new HashMap<>();
		mongo.find(query, ShiftAssignment.class)
				.forEach(assignment -> mine.put(assignment.getShiftId(), MyAssignment.of(assignment)));
		return mine;
	}

	private Set<String> eventIdsBySignUp(Set<String> projects, Instant from, Instant to, boolean open) {
		Criteria criteria = Criteria.where("projectId").in(projects)
				.and("startsAt").gt(from.minus(ShiftRules.EVENT_MAX)).lt(to)
				.and("endsAt").gt(from);
		criteria = open ? criteria.and("selfAssignable").ne(false) : criteria.and("selfAssignable").is(false);
		Query query = Query.query(criteria);
		query.fields().include("_id");
		return mongo.find(query, ShiftEvent.class).stream().map(ShiftEvent::getId).collect(Collectors.toSet());
	}

	private Map<String, ShiftEvent> eventsById(Collection<String> ids) {
		return byId(ids, ShiftEvent.class, ShiftEvent::getId);
	}

	private <T> Map<String, T> byId(Collection<String> ids, Class<T> type, Function<T, String> id) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<String, T> out = new LinkedHashMap<>();
		mongo.find(Query.query(Criteria.where("_id").in(ids)), type).forEach(row -> out.put(id.apply(row), row));
		return out;
	}

	/**
	 * HIN-162: the waiting lists of [shiftIds] in order, in one aggregation over the
	 * {@code waitlist_order} index; shifts nobody waits for are absent.
	 */
	Map<String, List<Waiter>> waitlists(Collection<String> shiftIds) {
		if (shiftIds.isEmpty()) {
			return Map.of();
		}
		AggregationOperation order = context -> new Document("$sort",
				new Document("shiftId", 1).append("createdAt", 1).append("_id", 1));
		AggregationOperation group = context -> new Document("$group", new Document("_id", "$shiftId")
				.append("queue", new Document("$push", new Document("a", "$_id").append("u", "$userId"))));
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("shiftId").in(shiftIds)
						.and("status").is(ShiftAssignment.Status.WAITING.name())),
				order, group);
		Map<String, List<Waiter>> out = new HashMap<>();
		for (Document row : mongo.aggregate(aggregation, mongo.getCollectionName(ShiftAssignment.class),
				Document.class)) {
			List<Waiter> queue = new ArrayList<>();
			for (Object raw : row.getList("queue", Object.class)) {
				Document entry = (Document) raw;
				queue.add(new Waiter(String.valueOf(entry.get("a")), entry.getString("u")));
			}
			out.put(String.valueOf(row.get("_id")), queue);
		}
		return out;
	}

	/** Names and pictures of [ids], in one read of the fields a card shows. */
	Map<String, UserCard> users(Collection<String> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		List<Object> stored = ids.stream().map(raw -> ObjectId.isValid(raw) ? (Object) new ObjectId(raw) : raw).toList();
		Query query = Query.query(Criteria.where("_id").in(stored));
		query.fields().include("displayName", "username", "avatarUrl");
		Map<String, UserCard> out = new LinkedHashMap<>();
		for (Document user : mongo.find(query, Document.class, mongo.getCollectionName(User.class))) {
			String name = user.getString("displayName");
			out.put(String.valueOf(user.get("_id")),
					new UserCard(name != null && !name.isBlank() ? name : user.getString("username"),
							user.getString("avatarUrl")));
		}
		return out;
	}

	private static int number(Document row, String key) {
		Object value = row.get(key);
		return value instanceof Number number ? number.intValue() : 0;
	}

	/** An event's numbers. */
	record Totals(int required, int taken, int understaffed) {

		static final Totals NONE = new Totals(0, 0, 0);

		static Totals of(List<Shift> shifts) {
			int required = 0;
			int taken = 0;
			int understaffed = 0;
			for (Shift shift : shifts) {
				required += shift.required();
				taken += Math.min(shift.assigned(), shift.required());
				understaffed += shift.understaffed() ? 1 : 0;
			}
			return new Totals(required, taken, understaffed);
		}
	}
}
