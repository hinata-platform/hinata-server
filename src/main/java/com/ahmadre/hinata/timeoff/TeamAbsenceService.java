package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.AvailabilityAccess;
import com.ahmadre.hinata.availability.CapacityService;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who in a group is away when, and what capacity the group has left (HIN-118).
 *
 * <p>The riskiest view of the block: a calendar of other people's absences becomes a tool for
 * watching them faster than any other screen in the module. So four things hold here and nowhere
 * else has to remember them.
 *
 * <ul>
 * <li><b>Off by default.</b> With {@code absenceCalendarVisibility} at {@code OFF} neither route
 * exists — 404 with the same body as a module that is off, for everybody, keepers included.</li>
 * <li><b>Coarsened before it leaves.</b> The level of the policy and the visibility of each type
 * are applied here, while the row is built, so a type the reader may not see is never in the
 * response — not hidden by the app, absent from the JSON. Sickness is "away" on every level and
 * for every reader of this view, the person themselves included: it is health data (Art. 9 DSGVO),
 * and a screen other people look over one's shoulder at is no place for it.</li>
 * <li><b>Requested shows, approved counts.</b> An open request is its own layer in the calendar
 * and in the clash counts, and lowers no capacity: a request withdrawn tomorrow must not have
 * changed today's plan.</li>
 * <li><b>A plan, not a register.</b> Spans of days, never times; rows by name, never by how much
 * somebody was away (R10).</li>
 * </ul>
 *
 * <p>Who belongs to a group is {@link AvailabilityAccess#roster}'s decision, not this class's.
 */
@Service
@RequiredArgsConstructor
public class TeamAbsenceService {

	/** Rows per page, as everywhere in the module. */
	public static final int PAGE_MAX = 100;

	/** The widest calendar window: a quarter, which is what the band shows at once. */
	public static final int CALENDAR_DAYS_MAX = 92;

	/** A daily band reaches a quarter; a weekly one a year. */
	public static final int BAND_DAYS_MAX = 92;
	public static final int BAND_WEEKS_DAYS_MAX = 366;

	/** Most open requests one calendar read collects across its rows. */
	static final int REQUESTS_MAX = 2_000;

	private final AvailabilityAccess access;
	private final CapacityService capacity;
	private final TimeTrackingSettings settings;
	private final TimeOffTypeService types;
	private final TimeOffRequestRepository requests;
	private final MongoTemplate mongo;

	/** How finely the band adds up. */
	public enum Resolution {
		DAY, WEEK
	}

	/**
	 * One absence or request on a row. The type fields are all null when the reader may only know
	 * that the person is away.
	 */
	public record Entry(LocalDate from, LocalDate to, boolean halfDay, boolean requested, String typeId,
			String typeKey, String typeSystemKey, String typeName, String icon, Integer hue) {
	}

	/** A holiday on a person's row, from the calendar they follow on that day. */
	public record HolidayEntry(LocalDate date, String name, boolean halfDay) {
	}

	public record Row(String userId, String name, String avatarUrl, List<HolidayEntry> holidays,
			List<Entry> entries) {
	}

	/**
	 * A page of rows, plus what the whole calendar needs once: the level in force and whether the
	 * group was cut at {@link CapacityService#GROUP_MAX}.
	 */
	public record CalendarPage(List<Row> content, int number, int size, long totalElements, int totalPages,
			TimePolicy.AbsenceCalendar visibility, LocalDate from, LocalDate to, boolean truncated) {
	}

	/**
	 * One day or week of the band. [away] and [requested] are the most people away on any day of
	 * the bucket — the peak is what a planner has to cover, and an average would hide it.
	 */
	public record Bucket(LocalDate from, LocalDate to, int scheduledMinutes, int capacityMinutes, int away,
			int requested) {
	}

	/** The band: sums only, never a person. */
	public record Band(LocalDate from, LocalDate to, Resolution resolution, int people, boolean truncated,
			List<Bucket> buckets) {
	}

	// ------------------------------------------------------------------ calendar

	/**
	 * A page of the group's rows from [from] to [to]. [awayOnly] keeps only the people with
	 * something to show in the window — the "away today" card asks for a single day this way.
	 */
	public CalendarPage calendar(User viewer, String teamId, String projectId, LocalDate from, LocalDate to,
			boolean awayOnly, int page, int size) {
		TimePolicy.AbsenceCalendar level = requireCalendar();
		assertSpan(from, to, CALENDAR_DAYS_MAX);
		int pageSize = Math.clamp(size, 1, PAGE_MAX);
		int pageIndex = Math.max(0, page);
		AvailabilityAccess.Roster roster = access.roster(viewer, teamId, projectId,
				AvailabilityAccess.Purpose.CALENDAR);
		Catalogue catalogue = new Catalogue();

		if (awayOnly) {
			CapacityService.Group group = capacity.group(roster.userIds(), from, to);
			Map<String, List<Entry>> entries = entriesOf(roster.userIds(), group, from, to, viewer, level, catalogue);
			entries.values().removeIf(List::isEmpty);
			List<Person> people = namesOf(entries.keySet());
			int total = people.size();
			List<Person> slice = people.stream().skip((long) pageIndex * pageSize).limit(pageSize).toList();
			return pageOf(slice, group, entries, pageIndex, pageSize, total, level, from, to, roster.truncated());
		}

		Criteria who = Criteria.where("_id").in(roster.userIds()).and("active").ne(false);
		long total = mongo.count(Query.query(who), User.class);
		Query query = Query.query(who)
				.with(PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Order.asc("displayName"), Sort.Order.asc("_id"))));
		List<Person> slice = people(query).toList();
		List<String> ids = slice.stream().map(Person::id).toList();
		CapacityService.Group group = capacity.group(ids, from, to);
		Map<String, List<Entry>> entries = entriesOf(ids, group, from, to, viewer, level, catalogue);
		return pageOf(slice, group, entries, pageIndex, pageSize, total, level, from, to, roster.truncated());
	}

	// ------------------------------------------------------------------ band

	/**
	 * What the group has left, day by day or week by week, and how many of it are away at the peak.
	 *
	 * <p>For who plans only (a lead of the project, an admin of the team, a keeper) and only with the
	 * calendar policy on — the band adds up the same days the calendar shows, so it exists exactly
	 * when the calendar does.
	 */
	public Band band(User viewer, String teamId, String projectId, LocalDate from, LocalDate to,
			Resolution resolution) {
		requireCalendar();
		Resolution step = resolution == null ? Resolution.DAY : resolution;
		assertSpan(from, to, step == Resolution.DAY ? BAND_DAYS_MAX : BAND_WEEKS_DAYS_MAX);
		AvailabilityAccess.Roster roster = access.roster(viewer, teamId, projectId,
				AvailabilityAccess.Purpose.BAND);
		CapacityService.Group group = capacity.group(roster.userIds(), from, to);

		List<TimeOffConflicts.Span> spans = new ArrayList<>();
		group.absences().forEach(off -> spans.add(new TimeOffConflicts.Span(off.userId(), off.from(), off.to(), true)));
		openRequests(roster.userIds(), from, to).forEach(request -> spans.add(
				new TimeOffConflicts.Span(request.getUserId(), request.getFrom(), request.getTo(), false)));
		List<TimeOffConflicts.Day> clashes = TimeOffConflicts.perDay(spans, from, to);

		List<Bucket> buckets = new ArrayList<>();
		for (int i = 0; i < group.capacity().size(); ) {
			LocalDate start = group.capacity().get(i).date();
			LocalDate end = step == Resolution.DAY ? start
					: min(start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), to);
			int scheduled = 0;
			int left = 0;
			int away = 0;
			int requested = 0;
			for (; i < group.capacity().size() && !group.capacity().get(i).date().isAfter(end); i++) {
				scheduled += group.capacity().get(i).scheduledMinutes();
				left += group.capacity().get(i).capacityMinutes();
				away = Math.max(away, clashes.get(i).away());
				requested = Math.max(requested, clashes.get(i).requested());
			}
			buckets.add(new Bucket(start, end, scheduled, left, away, requested));
		}
		return new Band(from, to, step, roster.userIds().size(), roster.truncated(), List.copyOf(buckets));
	}

	// ------------------------------------------------------------------ helpers

	/** The level in force; 404 like a module that is off when there is none. */
	private TimePolicy.AbsenceCalendar requireCalendar() {
		TimePolicy.AbsenceCalendar level = settings.absenceCalendarVisibility();
		if (level == TimePolicy.AbsenceCalendar.OFF) {
			throw new ApiException(HttpStatus.NOT_FOUND, AbsenceManagementGate.DISABLED_KEY);
		}
		return level;
	}

	private static void assertSpan(LocalDate from, LocalDate to, int maxDays) {
		if (from == null || to == null || to.isBefore(from)) {
			throw ApiException.badRequest("error.availability.windowInvalid");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
			throw ApiException.badRequest("error.availability.windowTooLong", maxDays);
		}
		CapacityService.assertWindow(from, to);
	}

	private record Person(String id, String name, String avatarUrl) {
	}

	/** Names for [ids], in the order the rows are shown: by name, never by anything else (R10). */
	private List<Person> namesOf(Collection<String> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return people(Query.query(Criteria.where("_id").in(ids).and("active").ne(false)))
				.sorted(Comparator.comparing((Person person) -> person.name() == null ? "" : person.name(),
						String.CASE_INSENSITIVE_ORDER).thenComparing(Person::id))
				.toList();
	}

	/** The name and picture of each person [query] finds, and nothing else of their account. */
	private java.util.stream.Stream<Person> people(Query query) {
		query.fields().include("_id").include("displayName").include("avatarUrl");
		return mongo.query(User.class).as(Document.class).matching(query).all().stream()
				.map(user -> new Person(String.valueOf(user.get("_id")), user.getString("displayName"),
						user.getString("avatarUrl")));
	}

	private CalendarPage pageOf(List<Person> slice, CapacityService.Group group, Map<String, List<Entry>> entries,
			int page, int size, long total, TimePolicy.AbsenceCalendar level, LocalDate from, LocalDate to,
			boolean truncated) {
		List<Row> rows = slice.stream()
				.map(person -> new Row(person.id(), person.name(), person.avatarUrl(),
						group.holidays().getOrDefault(person.id(), List.of()).stream()
								.map(mark -> new HolidayEntry(mark.date(), mark.name(), mark.halfDay())).toList(),
						entries.getOrDefault(person.id(), List.of())))
				.toList();
		int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
		return new CalendarPage(rows, page, size, total, pages, level, from, to, truncated);
	}

	/**
	 * Every entry of [userIds] a reader may see from [from] to [to], absences and open requests, each
	 * already coarsened. A person with nothing visible maps to an empty list.
	 */
	private Map<String, List<Entry>> entriesOf(List<String> userIds, CapacityService.Group group, LocalDate from,
			LocalDate to, User viewer, TimePolicy.AbsenceCalendar level, Catalogue catalogue) {
		Map<String, List<Entry>> entries = new LinkedHashMap<>();
		userIds.forEach(id -> entries.put(id, new ArrayList<>()));
		if (userIds.isEmpty()) {
			return entries;
		}
		List<TimeOffRequest> open = openRequests(userIds, from, to);
		Set<String> typeIds = new HashSet<>();
		group.absences().forEach(off -> typeIds.add(off.typeId()));
		open.forEach(request -> typeIds.add(request.getTypeId()));
		catalogue.load(typeIds);

		for (CapacityService.PersonAbsence off : group.absences()) {
			TimeOffType type = off.typeId() != null ? catalogue.byId(off.typeId()) : catalogue.system(off.type());
			shown(level, type, off.type() == TimeOff.Type.SICK, off.userId().equals(viewer.getId()))
					.ifPresent(sight -> entries.get(off.userId()).add(
							entry(off.from(), off.to(), off.halfDay(), false, sight, type)));
		}
		for (TimeOffRequest request : open) {
			TimeOffType type = catalogue.byId(request.getTypeId());
			boolean halfDay = request.getFrom().equals(request.getTo()) && request.getFirstDayMilliDays() != null
					&& request.getFirstDayMilliDays() < TimeOffType.DAY;
			shown(level, type, false, request.getUserId().equals(viewer.getId()))
					.ifPresent(sight -> entries.get(request.getUserId()).add(
							entry(request.getFrom(), request.getTo(), halfDay, true, sight, type)));
		}
		entries.values().forEach(list -> list.sort(Comparator.comparing(Entry::from)));
		return entries;
	}

	private List<TimeOffRequest> openRequests(List<String> userIds, LocalDate from, LocalDate to) {
		if (userIds.isEmpty()) {
			return List.of();
		}
		return requests.findByUserIdInAndStatusAndToGreaterThanEqualAndFromLessThanEqual(userIds,
				TimeOffRequest.Status.SUBMITTED, from, to, PageRequest.of(0, REQUESTS_MAX));
	}

	/**
	 * What of one absence a reader may see: its type, only that it exists, or nothing.
	 *
	 * <p>The narrower of the policy and the type's own visibility, with two fixed points: sickness
	 * is never more than "away", and a type kept to the person themselves is still shown on their
	 * own row, because it is theirs.
	 */
	static Optional<TimePolicy.AbsenceCalendar> shown(TimePolicy.AbsenceCalendar level, TimeOffType type,
			boolean storedSick, boolean own) {
		if (level == TimePolicy.AbsenceCalendar.OFF) {
			return Optional.empty();
		}
		boolean sick = storedSick || type != null && type.getKind() == TimeOffType.Kind.SICK;
		TimeOffType.Visibility visibility = type == null ? TimeOffType.Visibility.BUSY_ONLY : type.visibility();
		if (own) {
			visibility = TimeOffType.Visibility.TYPE;
		}
		if (visibility == TimeOffType.Visibility.SELF_ONLY) {
			return Optional.empty();
		}
		boolean typed = level == TimePolicy.AbsenceCalendar.TYPE && visibility == TimeOffType.Visibility.TYPE && !sick;
		return Optional.of(typed ? TimePolicy.AbsenceCalendar.TYPE : TimePolicy.AbsenceCalendar.BUSY_ONLY);
	}

	private static Entry entry(LocalDate from, LocalDate to, boolean halfDay, boolean requested,
			TimePolicy.AbsenceCalendar sight, TimeOffType type) {
		if (sight != TimePolicy.AbsenceCalendar.TYPE || type == null) {
			return new Entry(from, to, halfDay, requested, null, null, null, null, null, null);
		}
		return new Entry(from, to, halfDay, requested, type.getId(), type.getKey(), type.getSystemKey(),
				type.getName(), type.getIcon(), type.getHue());
	}

	private static LocalDate min(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}

	/**
	 * The types one read needs, fetched once, and the three built-in ones an absence from before the
	 * catalogue points at through its stored kind alone.
	 */
	private final class Catalogue {

		private final Map<String, TimeOffType> byId = new HashMap<>();
		private final Map<TimeOff.Type, TimeOffType> system = new HashMap<>();

		void load(Set<String> ids) {
			ids.remove(null);
			ids.removeAll(byId.keySet());
			types.findAllById(ids).forEach(type -> byId.put(type.getId(), type));
		}

		TimeOffType byId(String id) {
			return id == null ? null : byId.get(id);
		}

		TimeOffType system(TimeOff.Type kind) {
			if (kind == null) {
				return null;
			}
			return system.computeIfAbsent(kind, stored -> types.byKey(switch (stored) {
				case VACATION -> TimeOffType.SYSTEM_VACATION;
				case SICK -> TimeOffType.SYSTEM_SICK;
				case OTHER -> TimeOffType.SYSTEM_OTHER;
			}).orElse(null));
		}
	}
}
