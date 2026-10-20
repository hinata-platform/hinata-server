package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Series of events (HIN-161): creating one, materialising its occurrences ahead, and the writes
 * that change a series from one of its occurrences on.
 *
 * <p><b>Materialising.</b> Occurrences exist up to {@code min(today + 90 days, the 52nd occurrence)},
 * today in the series' zone, and the nightly {@link ShiftSeriesJob} pulls them along. Each run is
 * one transaction per series whose first write is the compare-and-set on {@code rev}; it then reads
 * which of its days already exist and inserts only the others. Two runs at once, a run beside a
 * split, a retried run: the second one either collides with the first and is retried on what the
 * first one left, or finds a different rev and stops. Nothing is ever created twice, and the
 * unique index on the occurrence day is only the net under it.
 *
 * <p><b>Scope.</b> Every write to an occurrence names its scope ({@link Scope}). "Only this one"
 * detaches the occurrence, which then keeps its series id for the label and follows no further
 * change; cancelling or deleting it also leaves its day out of the series. "All future ones" closes
 * the series the day before and starts a new one with the changed values; see
 * {@link #changeFuture} and {@link #endFuture}. Neither reaches into the past.
 *
 * <p>Audit records and notices are published from inside the transaction and delivered by
 * {@link ShiftDelivery} after the commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftSeriesService {

	/** Most open series one nightly run reads; the next night takes the rest. */
	static final int NIGHTLY_BATCH = 5_000;

	private final MongoTemplate mongo;
	private final ShiftTx tx;
	private final ShiftAccess access;
	private final ShiftSettings settings;
	private final SettingsService serverSettings;
	private final ApplicationEventPublisher events;
	private final ShiftWarnings collisions;
	private final Clock clock;

	/** Which occurrences a write to an occurrence of a series means. */
	public enum Scope {
		THIS, FUTURE;

		/** "this" or "future", any case; null when absent; anything else is a refusal. */
		static Scope parse(String raw) {
			if (raw == null || raw.isBlank()) {
				return null;
			}
			return switch (raw.strip().toLowerCase(Locale.ROOT)) {
				case "this" -> THIS;
				case "future" -> FUTURE;
				default -> throw ShiftRules.refusal(HttpStatus.BAD_REQUEST, "error.shifts.seriesScopeRequired");
			};
		}
	}

	/** One shift of every occurrence, in wall-clock times of the series' zone. */
	public record TemplateDraft(String key, String title, Integer dayOffset, LocalTime start, LocalTime end,
			Integer requiredHelpers, Integer maxHelpers) {
	}

	/**
	 * A new series. {@code firstStart} is the wall-clock start of the first occurrence in
	 * {@code timezone} (the creator's zone when absent), {@code durationMinutes} how long each
	 * occurrence lasts, {@code rrule} the RFC 5545 rule in the supported subset.
	 */
	public record SeriesDraft(String title, String description, String location, String timezone,
			LocalDateTime firstStart, Integer durationMinutes, String rrule, Boolean selfAssignable,
			List<TemplateDraft> shifts) {
	}

	/** A created series and how many occurrences its first horizon brought. */
	public record Created(ShiftSeries series, int occurrences) {
	}

	/** One upcoming occurrence of a series. */
	public record Upcoming(LocalDate date, Instant startsAt, Instant endsAt) {
	}

	/** What a change from an occurrence on did: the occurrence as it is now and who now collides. */
	public record FutureChange(ShiftEvent event, ShiftSeries series, List<ShiftService.Warning> warnings) {
	}

	/**
	 * A series from an occurrence on, as a change describes it. [rrule] null keeps the old rule
	 * (with COUNT counted on); [adopt] gives shifts of the occurrence that had no template the key
	 * they now carry, before the occurrences are matched against the templates.
	 */
	record Shape(String title, String description, String location, String timezone, LocalDateTime firstStart,
			int durationMinutes, String rrule, boolean selfAssignable, List<ShiftTemplate> templates,
			Map<String, String> adopt) {
	}

	// --- scope ---------------------------------------------------------------------------

	/** The scope a write to [event] runs in; null for an event outside a series. */
	public Scope scopeOf(ShiftEvent event, String raw) {
		Scope scope = Scope.parse(raw);
		if (!event.inSeries()) {
			return null;
		}
		if (scope == null) {
			throw ShiftRules.refusal(HttpStatus.BAD_REQUEST, "error.shifts.seriesScopeRequired");
		}
		return scope;
	}

	/** Inside a write's transaction: the occurrence no longer follows its series. */
	void detach(ShiftEvent event) {
		if (event.inSeries() && !event.isDetached()) {
			mongo.updateFirst(Query.query(Criteria.where("_id").is(event.getId())),
					new Update().set("detached", true), ShiftEvent.class);
		}
	}

	/**
	 * Inside the transaction of a cancellation or deletion of one occurrence: it is detached, and its
	 * day is left out of the series, so a split carries the gap over and nothing fills it again.
	 */
	void exclude(ShiftEvent event) {
		if (!event.inSeries()) {
			return;
		}
		detach(event);
		LocalDate day = event.getOccurrenceDate();
		UpdateResult added = mongo.updateFirst(Query.query(Criteria.where("_id").is(event.getSeriesId())
				.and("exdates." + (ShiftSeriesPlan.EXDATES_MAX - 1)).exists(false)),
				new Update().addToSet("exdates", day).inc("rev", 1).set("updatedAt", clock.instant()),
				ShiftSeries.class);
		if (added.getMatchedCount() == 0) {
			ShiftSeries series = mongo.findById(event.getSeriesId(), ShiftSeries.class);
			if (series != null && !series.getExdates().contains(day)) {
				throw ApiException.badRequest("error.shifts.seriesTooManyExdates", ShiftSeriesPlan.EXDATES_MAX);
			}
		}
	}

	// --- creating and reading ------------------------------------------------------------

	public Created create(User caller, String projectId, SeriesDraft draft) {
		settings.requireEnabled();
		Project project = access.plannedProject(projectId, caller);
		ShiftSeries probe = fromDraft(caller, project.getId(), draft);
		LocalDate today = today(probe);
		ShiftSeriesPlan.validate(probe, today);
		ShiftSeriesPlan.assertHorizonFits(probe, ShiftSeriesPlan.horizon(probe, today).dates().size());
		return tx.run(() -> {
			ShiftSeries series = fromDraft(caller, project.getId(), draft);
			ShiftSeriesPlan.validate(series, today);
			series.setShifts(probe.getShifts());
			series.setRev(null);
			ShiftSeries saved = mongo.insert(series);
			int created = materialize(saved, today, caller.getId());
			audited(AuditAction.SHIFT_SERIES_CREATED, caller, saved.getId(), Map.of("projectId", saved.getProjectId(),
					"occurrences", String.valueOf(created)));
			return new Created(mongo.findById(saved.getId(), ShiftSeries.class), created);
		});
	}

	/**
	 * What a draft would make, without saving it: the label is the controller's, the days are here.
	 * The title may still be missing; everything else is checked as for {@link #create}.
	 */
	public ShiftSeries preview(User caller, String projectId, SeriesDraft draft) {
		settings.requireEnabled();
		Project project = access.plannedProject(projectId, caller);
		ShiftSeries series = fromDraft(caller, project.getId(), draft);
		ShiftSeriesPlan.validate(series, today(series), false);
		return series;
	}

	/** A series the caller can see; 404 otherwise. */
	public ShiftAccess.Scoped<ShiftSeries> visible(User caller, String seriesId) {
		settings.requireEnabled();
		return access.visibleSeries(seriesId, caller);
	}

	/**
	 * The first occurrence from today on (in the series' zone) that exists and is not cancelled, or
	 * null: the event the app opens after creating or reading a series. One read over
	 * {@code series_day}.
	 */
	public String firstEventId(ShiftSeries series) {
		Query query = Query.query(Criteria.where("seriesId").is(series.getId())
				.and("occurrenceDate").gte(today(series))
				.and("cancelled").ne(true))
				.with(Sort.by("occurrenceDate"));
		query.fields().include("_id");
		ShiftEvent first = mongo.findOne(query, ShiftEvent.class);
		return first == null ? null : first.getId();
	}

	/** The next [count] occurrences from today on, exdates left out, as they would be or are. */
	public List<Upcoming> upcoming(ShiftSeries series, int count) {
		LocalDate today = today(series);
		LocalDate from = ShiftSeriesPlan.later(today, series.getFirstStart().toLocalDate());
		List<LocalDate> dates = ShiftSeriesPlan.dates(series, from, from.plusYears(2));
		return dates.stream().limit(count).map(date -> {
			ShiftSeriesPlan.Occurrence occurrence = ShiftSeriesPlan.occurrence(series, date);
			return new Upcoming(date, occurrence.startsAt(), occurrence.endsAt());
		}).toList();
	}

	// --- materialising -------------------------------------------------------------------

	/**
	 * The nightly run: every open series whose horizon ends before today + 90 days is pulled along,
	 * each in its own transaction. A series whose project is gone is marked finished and left. With
	 * the module off nothing happens; after months off, the first run starts at today, never in
	 * the past. Returns how many occurrences were created.
	 */
	public int nightly() {
		if (!settings.enabled()) {
			return 0;
		}
		LocalDate bound = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(ShiftSeriesPlan.HORIZON_DAYS + 1);
		Query open = Query.query(Criteria.where("finished").is(false).and("materializedUntil").lt(bound))
				.limit(NIGHTLY_BATCH);
		open.fields().include("_id", "projectId");
		List<ShiftSeries> due = mongo.find(open, ShiftSeries.class);
		if (due.isEmpty()) {
			return 0;
		}
		Set<String> alive = existingProjects(due.stream().map(ShiftSeries::getProjectId).collect(Collectors.toSet()));
		int created = 0;
		for (ShiftSeries candidate : due) {
			try {
				if (!alive.contains(candidate.getProjectId())) {
					mongo.updateFirst(Query.query(Criteria.where("_id").is(candidate.getId())),
							new Update().set("finished", true).inc("rev", 1), ShiftSeries.class);
					continue;
				}
				created += materialize(candidate.getId());
			}
			catch (RuntimeException failure) {
				log.warn("[shifts] series {} could not be pulled along: {}", candidate.getId(), failure.toString());
			}
		}
		return created;
	}

	/** One series pulled along in its own transaction; also what two parallel runs race on. */
	int materialize(String seriesId) {
		return tx.run(() -> {
			ShiftSeries series = mongo.findById(seriesId, ShiftSeries.class);
			if (series == null || series.isFinished()) {
				return 0;
			}
			return materialize(series, today(series), series.getCreatedBy());
		});
	}

	/**
	 * Inside a transaction: claims [series] at the rev it was read with, moves its horizon on,
	 * trims exdates that are past, and inserts the occurrences of the new days that do not exist
	 * yet. A claim that does not match means somebody else moved the series on; nothing is written.
	 */
	private int materialize(ShiftSeries series, LocalDate today, String createdBy) {
		ShiftSeriesPlan.Horizon horizon = ShiftSeriesPlan.horizon(series, today);
		LocalDate until = horizon.until();
		boolean finished = until != null && ShiftSeriesPlan.endsBy(series, until);
		List<LocalDate> exdates = series.getExdates() == null ? List.of()
				: series.getExdates().stream().filter(day -> !day.isBefore(today)).toList();
		boolean trimmed = series.getExdates() != null && exdates.size() != series.getExdates().size();
		if (horizon.dates().isEmpty() && !trimmed && finished == series.isFinished()
				&& Objects.equals(until, series.getMaterializedUntil())) {
			return 0;
		}
		Update claim = new Update().set("materializedUntil", until).set("finished", finished).inc("rev", 1)
				.set("updatedAt", clock.instant());
		if (trimmed) {
			claim.set("exdates", exdates);
		}
		UpdateResult claimed = mongo.updateFirst(Query.query(Criteria.where("_id").is(series.getId())
				.and("rev").is(series.revision())), claim, ShiftSeries.class);
		if (claimed.getMatchedCount() == 0 || horizon.dates().isEmpty()) {
			return 0;
		}
		Set<LocalDate> existing = existingDays(series.getId(), horizon.dates());
		List<ShiftEvent> fresh = new ArrayList<>();
		for (LocalDate day : horizon.dates()) {
			if (!existing.contains(day)) {
				fresh.add(occurrenceEvent(series, day, createdBy));
			}
		}
		insertOccurrences(series, fresh);
		return fresh.size();
	}

	private Set<LocalDate> existingDays(String seriesId, Collection<LocalDate> days) {
		Query query = Query.query(Criteria.where("seriesId").is(seriesId).and("occurrenceDate").in(days));
		query.fields().include("occurrenceDate");
		return mongo.find(query, ShiftEvent.class).stream().map(ShiftEvent::getOccurrenceDate)
				.collect(Collectors.toSet());
	}

	/** New occurrences with the series' shifts: one bulk insert of events, one of their shifts. */
	private void insertOccurrences(ShiftSeries series, List<ShiftEvent> fresh) {
		if (fresh.isEmpty()) {
			return;
		}
		Collection<ShiftEvent> saved = mongo.insertAll(fresh);
		List<Shift> shifts = new ArrayList<>();
		for (ShiftEvent event : saved) {
			for (ShiftTemplate template : series.getShifts()) {
				shifts.add(occurrenceShift(series, event, template));
			}
		}
		if (!shifts.isEmpty()) {
			mongo.insertAll(shifts);
		}
	}

	private ShiftEvent occurrenceEvent(ShiftSeries series, LocalDate day, String createdBy) {
		ShiftSeriesPlan.Occurrence occurrence = ShiftSeriesPlan.occurrence(series, day);
		return ShiftEvent.builder()
				.projectId(series.getProjectId())
				.title(series.getTitle())
				.description(series.getDescription())
				.location(series.getLocation())
				.startsAt(occurrence.startsAt()).endsAt(occurrence.endsAt())
				.timezone(series.getTimezone())
				.cancelled(false)
				.selfAssignable(series.isSelfAssignable())
				.createdBy(createdBy)
				.seriesId(series.getId())
				.occurrenceDate(day)
				.detached(false)
				.build();
	}

	private Shift occurrenceShift(ShiftSeries series, ShiftEvent event, ShiftTemplate template) {
		ShiftSeriesPlan.ShiftTimes times = ShiftSeriesPlan.shiftTimes(series, template, event.getOccurrenceDate());
		return Shift.builder()
				.eventId(event.getId())
				.projectId(event.getProjectId())
				.title(template.getTitle())
				.startsAt(times.startsAt()).endsAt(times.endsAt())
				.requiredHelpers(template.required())
				.maxHelpers(template.getMaxHelpers())
				.assignedCount(0)
				.cancelled(event.isCancelled())
				.deleted(false)
				.templateKey(template.getKey())
				.build();
	}

	// --- "all future ones" ---------------------------------------------------------------

	/** "All future ones" for an event change: the occurrence's values and shifts become the series'. */
	public FutureChange changeEventFuture(User caller, ShiftEvent occurrence, ShiftService.EventPatch patch) {
		return changeFuture(caller, occurrence, (series, event) -> {
			String zone = patch.timezone() != null ? normalizedZone(patch.timezone(), event.getTimezone())
					: event.getTimezone();
			ZoneId zoneId = ShiftRules.zoneOf(zone);
			Instant start = patch.startsAt() != null ? ShiftRules.stored(patch.startsAt()) : event.getStartsAt();
			long delta = Duration.between(event.getStartsAt(), start).toMillis();
			Instant end = patch.endsAt() != null ? ShiftRules.stored(patch.endsAt()) : event.getEndsAt().plusMillis(delta);
			ShiftRules.eventSpan(start, end);
			LocalDateTime startLocal = LocalDateTime.ofInstant(start, zoneId);
			LocalDateTime endLocal = LocalDateTime.ofInstant(end, zoneId);
			Map<String, String> adopt = new LinkedHashMap<>();
			List<ShiftTemplate> templates = new ArrayList<>();
			for (Shift shift : liveShifts(event.getId())) {
				String key = shift.getTemplateKey();
				if (key == null) {
					key = ShiftSeriesPlan.newKey();
					adopt.put(shift.getId(), key);
				}
				Shift moved = Shift.builder().title(shift.getTitle()).startsAt(shift.getStartsAt().plusMillis(delta))
						.endsAt(shift.getEndsAt().plusMillis(delta)).requiredHelpers(shift.getRequiredHelpers())
						.maxHelpers(shift.getMaxHelpers()).build();
				templates.add(ShiftSeriesPlan.templateOf(moved, startLocal, zoneId, key));
			}
			return new Shape(
					patch.title() != null ? patch.title() : event.getTitle(),
					patch.description() != null ? blankToNull(patch.description()) : event.getDescription(),
					patch.location() != null ? blankToNull(patch.location()) : event.getLocation(),
					zone, startLocal, (int) Duration.between(startLocal, endLocal).toMinutes(),
					patch.rrule() == null || patch.rrule().isBlank() ? null : patch.rrule().strip(),
					patch.selfAssignable() != null ? patch.selfAssignable() : event.isSelfAssignable(),
					templates, adopt);
		});
	}

	/** "All future ones" for a new shift: it becomes a template of the series from this day on. */
	public FutureChange addShiftFuture(User caller, ShiftEvent occurrence, ShiftService.ShiftDraft draft, String key) {
		return changeFuture(caller, occurrence, (series, event) -> {
			ShiftRules.shiftSpan(ShiftRules.stored(draft.startsAt()), ShiftRules.stored(draft.endsAt()), event);
			Shift drafted = Shift.builder().title(draft.title()).startsAt(draft.startsAt()).endsAt(draft.endsAt())
					.requiredHelpers(draft.requiredHelpers()).maxHelpers(draft.maxHelpers()).build();
			List<ShiftTemplate> templates = new ArrayList<>(copies(series.getShifts()));
			templates.add(templateAt(series, event, drafted, key));
			return sameSeries(series, event, templates, Map.of());
		});
	}

	/** "All future ones" for a changed shift: its template changes from this day on. */
	public FutureChange changeShiftFuture(User caller, ShiftEvent occurrence, Shift shift, Shift changed) {
		return changeFuture(caller, occurrence, (series, event) -> {
			String key = shift.getTemplateKey() != null ? shift.getTemplateKey() : ShiftSeriesPlan.newKey();
			List<ShiftTemplate> templates = new ArrayList<>();
			boolean found = false;
			for (ShiftTemplate template : copies(series.getShifts())) {
				if (template.getKey().equals(key)) {
					templates.add(templateAt(series, event, changed, key));
					found = true;
				}
				else {
					templates.add(template);
				}
			}
			if (!found) {
				templates.add(templateAt(series, event, changed, key));
			}
			return sameSeries(series, event, templates,
					shift.getTemplateKey() == null ? Map.of(shift.getId(), key) : Map.of());
		});
	}

	/** "All future ones" for a deleted shift: its template goes from this day on. */
	public FutureChange removeShiftFuture(User caller, ShiftEvent occurrence, Shift shift) {
		return changeFuture(caller, occurrence, (series, event) -> {
			List<ShiftTemplate> templates = copies(series.getShifts()).stream()
					.filter(template -> !template.getKey().equals(shift.getTemplateKey())).toList();
			return sameSeries(series, event, new ArrayList<>(templates), Map.of());
		});
	}

	/**
	 * The heart of "all future ones". The series is closed the day before the occurrence (COUNT
	 * gone, UNTIL set), a new one starts on the occurrence with the changed values, its COUNT
	 * counted on, its exdates and the days of detached occurrences carried over. The future,
	 * not detached occurrences move over as one bulk write each for events, shifts and assignments,
	 * their times computed afresh from the new series' wall clock; shifts are matched to the
	 * templates by key, a shift without one stays as it is, a removed template deletes only shifts
	 * nobody holds (409 otherwise, nothing written). Occurrences the new rule no longer has go as
	 * a deleted series' do, days it newly has are created. Everybody whose place moved, was
	 * cancelled or went hears once; the planner sees who now collides.
	 */
	FutureChange changeFuture(User caller, ShiftEvent occurrence, BiFunction<ShiftSeries, ShiftEvent, Shape> change) {
		settings.requireEnabled();
		Set<String> moved = new LinkedHashSet<>();
		FutureChange done = tx.run(() -> {
			moved.clear();
			ShiftEvent event = mongo.findById(occurrence.getId(), ShiftEvent.class);
			ShiftSeries series = event == null ? null : mongo.findById(event.getSeriesId(), ShiftSeries.class);
			if (series == null) {
				throw ApiException.notFound("shiftEvent");
			}
			LocalDate from = event.getOccurrenceDate();
			LocalDate today = today(series);
			if (from.isBefore(today)) {
				throw ApiException.badRequest("error.shifts.seriesFromInPast");
			}
			Shape shape = change.apply(series, event);
			LocalDate start = shape.firstStart().toLocalDate();
			long delta = ChronoUnit.DAYS.between(from, start);
			List<ShiftEvent> future = futureOccurrences(series, from, event);
			ShiftSeries next = nextSeries(caller, series, shape, from, delta, future, event);
			ShiftSeriesPlan.validate(next, today);
			List<LocalDate> nextDates = ShiftSeriesPlan.dates(next, start, next.getMaterializedUntil());
			ShiftSeriesPlan.assertHorizonFits(next, nextDates.size());

			// Which occurrence goes to which day of the new series: a day moves by the occurrence's own move.
			Set<LocalDate> open = new HashSet<>(nextDates);
			Map<LocalDate, ShiftEvent> carried = new TreeMap<>();
			List<ShiftEvent> dropped = new ArrayList<>();
			for (ShiftEvent old : future) {
				LocalDate day = old.getOccurrenceDate().plusDays(delta);
				if (open.remove(day)) {
					carried.put(day, old);
				}
				else {
					dropped.add(old);
				}
			}
			if (carried.values().stream().noneMatch(old -> old.getId().equals(event.getId()))) {
				// The occurrence itself always opens the new series, on its own new day.
				dropped.removeIf(old -> old.getId().equals(event.getId()));
				carried.put(start, event);
				open.remove(start);
			}

			shape.adopt().forEach((shiftId, key) -> mongo.updateFirst(Query.query(Criteria.where("_id").is(shiftId)),
					new Update().set("templateKey", key), Shift.class));
			Map<String, ShiftTemplate> templates = new LinkedHashMap<>();
			next.getShifts().forEach(template -> templates.put(template.getKey(), template));
			Map<String, LocalDate> dayOf = new HashMap<>();
			carried.forEach((day, old) -> dayOf.put(old.getId(), day));
			List<Shift> shifts = dayOf.isEmpty() ? List.of() : mongo.find(Query.query(Criteria.where("eventId")
					.in(dayOf.keySet()).and("deleted").is(false)), Shift.class);
			refuseWhatCannotChange(shifts, templates);

			UpdateResult claimed = mongo.updateFirst(Query.query(Criteria.where("_id").is(series.getId())
					.and("rev").is(series.revision())),
					new Update().set("rrule", ShiftSeriesPlan.closedOn(series.getRrule(), from.minusDays(1)))
							.set("finished", true).inc("rev", 1).set("updatedAt", clock.instant()),
					ShiftSeries.class);
			if (claimed.getMatchedCount() == 0) {
				throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.busy");
			}
			ShiftSeries saved = mongo.insert(next);

			rehang(saved, carried);
			Map<String, Integer> counts = new HashMap<>();
			Set<String> reachedShifts = new LinkedHashSet<>();
			retime(saved, shifts, dayOf, templates, moved, reachedShifts);
			Map<String, List<String>> keysByEvent = new HashMap<>();
			for (Shift shift : shifts) {
				if (shift.getTemplateKey() != null || shape.adopt().containsKey(shift.getId())) {
					String key = shift.getTemplateKey() != null ? shift.getTemplateKey() : shape.adopt().get(shift.getId());
					keysByEvent.computeIfAbsent(shift.getEventId(), id -> new ArrayList<>()).add(key);
				}
			}
			List<Shift> added = new ArrayList<>();
			for (ShiftEvent old : carried.values()) {
				List<String> has = keysByEvent.getOrDefault(old.getId(), List.of());
				ShiftEvent now = ShiftEvent.builder().id(old.getId()).projectId(old.getProjectId())
						.occurrenceDate(dayOf.get(old.getId())).cancelled(old.isCancelled()).build();
				for (ShiftTemplate template : saved.getShifts()) {
					if (!has.contains(template.getKey())) {
						added.add(occurrenceShift(saved, now, template));
					}
				}
			}
			if (!added.isEmpty()) {
				mongo.insertAll(added);
			}
			if (!reachedShifts.isEmpty()) {
				count(Criteria.where("shiftId").in(reachedShifts), counts);
			}
			Ended ended = end(dropped, false, counts);
			List<ShiftEvent> fresh = open.stream().sorted().map(day -> occurrenceEvent(saved, day, caller.getId()))
					.toList();
			insertOccurrences(saved, new ArrayList<>(fresh));

			audited(AuditAction.SHIFT_SERIES_UPDATED, caller, saved.getId(), Map.of("projectId", saved.getProjectId(),
					"previousSeriesId", series.getId(), "occurrences", String.valueOf(carried.size() + fresh.size()),
					"occurrencesRemoved", String.valueOf(ended.removed()),
					"occurrencesCancelled", String.valueOf(ended.cancelled())));
			notifyChanged(saved.getProjectId(), caller, counts);
			return new FutureChange(mongo.findById(event.getId(), ShiftEvent.class), saved, List.of());
		});
		List<ShiftService.Warning> warnings = moved.isEmpty() ? List.of()
				: collisions.of(caller, Criteria.where("shiftId").in(moved));
		return new FutureChange(done.event(), done.series(), warnings);
	}

	/**
	 * "All future ones" for a deletion ([cancel] false) or a cancellation ([cancel] true): the series
	 * ends the day before; every future, not detached occurrence is tombstoned first, then those
	 * nobody holds a place on are deleted and the others cancelled (a cancellation cancels them all).
	 * Past occurrences stay. Everybody who loses a place hears once.
	 */
	public void endFuture(User caller, ShiftEvent occurrence, boolean cancel) {
		settings.requireEnabled();
		tx.run(() -> {
			ShiftEvent event = mongo.findById(occurrence.getId(), ShiftEvent.class);
			ShiftSeries series = event == null ? null : mongo.findById(event.getSeriesId(), ShiftSeries.class);
			if (series == null) {
				throw ApiException.notFound("shiftEvent");
			}
			LocalDate from = event.getOccurrenceDate();
			if (from.isBefore(today(series))) {
				throw ApiException.badRequest("error.shifts.seriesFromInPast");
			}
			UpdateResult claimed = mongo.updateFirst(Query.query(Criteria.where("_id").is(series.getId())
					.and("rev").is(series.revision())),
					new Update().set("rrule", ShiftSeriesPlan.closedOn(series.getRrule(), from.minusDays(1)))
							.set("finished", true).inc("rev", 1).set("updatedAt", clock.instant()),
					ShiftSeries.class);
			if (claimed.getMatchedCount() == 0) {
				throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.busy");
			}
			Map<String, Integer> counts = new HashMap<>();
			Ended ended = end(futureOccurrences(series, from, event), cancel, counts);
			audited(cancel ? AuditAction.SHIFT_SERIES_UPDATED : AuditAction.SHIFT_SERIES_DELETED, caller,
					series.getId(), Map.of("projectId", series.getProjectId(),
							"occurrencesRemoved", String.valueOf(ended.removed()),
							"occurrencesCancelled", String.valueOf(ended.cancelled())));
			notifyChanged(series.getProjectId(), caller, counts);
		});
	}

	// --- helpers -------------------------------------------------------------------------

	/** The future occurrences of [series] from [from] on that still follow it, and [event] itself. */
	private List<ShiftEvent> futureOccurrences(ShiftSeries series, LocalDate from, ShiftEvent event) {
		List<ShiftEvent> future = new ArrayList<>(mongo.find(Query.query(Criteria.where("seriesId").is(series.getId())
				.and("occurrenceDate").gte(from).and("detached").ne(true))
				.with(Sort.by("occurrenceDate")), ShiftEvent.class));
		if (future.stream().noneMatch(found -> found.getId().equals(event.getId()))) {
			future.add(0, event);
		}
		return future;
	}

	/** The series that continues [series] from [from] on, in [shape]. */
	private ShiftSeries nextSeries(User caller, ShiftSeries series, Shape shape, LocalDate from, long delta,
			List<ShiftEvent> future, ShiftEvent event) {
		String rrule = shape.rrule();
		if (rrule == null) {
			Map<String, String> parts = ShiftSeriesPlan.parts(series.getRrule());
			rrule = parts.containsKey("COUNT")
					? ShiftSeriesPlan.withCount(series.getRrule(),
							Integer.parseInt(parts.get("COUNT")) - ShiftSeriesPlan.countBefore(series, from))
					: series.getRrule();
		}
		LocalDate start = shape.firstStart().toLocalDate();
		Set<LocalDate> exdates = new java.util.TreeSet<>();
		for (LocalDate day : series.getExdates()) {
			if (!day.isBefore(from)) {
				exdates.add(day.plusDays(delta));
			}
		}
		// Detached occurrences ahead stay where they are; the new series leaves their days out.
		Query detached = Query.query(Criteria.where("seriesId").is(series.getId()).and("occurrenceDate").gte(from)
				.and("detached").is(true).and("_id").ne(event.getId()));
		detached.fields().include("occurrenceDate");
		mongo.find(detached, ShiftEvent.class).forEach(found -> exdates.add(found.getOccurrenceDate().plusDays(delta)));
		exdates.remove(start);
		LocalDate until = series.getMaterializedUntil() == null ? start
				: ShiftSeriesPlan.later(series.getMaterializedUntil(), start);
		return ShiftSeries.builder()
				.projectId(series.getProjectId())
				.title(shape.title())
				.description(shape.description())
				.location(shape.location())
				.timezone(shape.timezone())
				.firstStart(shape.firstStart())
				.durationMinutes(shape.durationMinutes())
				.rrule(rrule)
				.exdates(new ArrayList<>(exdates))
				.shifts(shape.templates())
				.selfAssignable(shape.selfAssignable())
				.materializedUntil(until)
				.finished(false)
				.createdBy(caller.getId())
				.build();
	}

	/** The series as it is, starting on [event]'s day at its own time, with other [templates]. */
	private static Shape sameSeries(ShiftSeries series, ShiftEvent event, List<ShiftTemplate> templates,
			Map<String, String> adopt) {
		return new Shape(series.getTitle(), series.getDescription(), series.getLocation(), series.getTimezone(),
				event.getOccurrenceDate().atTime(series.getFirstStart().toLocalTime()), series.duration(), null,
				series.isSelfAssignable(), templates, adopt);
	}

	/** A template from [shift] placed in the occurrence on [event]'s day, at the series' own start. */
	private static ShiftTemplate templateAt(ShiftSeries series, ShiftEvent event, Shift shift, String key) {
		ZoneId zone = ShiftRules.zoneOf(series.getTimezone());
		ShiftTemplate template = ShiftSeriesPlan.templateOf(shift,
				event.getOccurrenceDate().atTime(series.getFirstStart().toLocalTime()), zone, key);
		ShiftRules.helpers(template.required(), template.getMaxHelpers());
		return template;
	}

	private static List<ShiftTemplate> copies(List<ShiftTemplate> templates) {
		return templates == null ? List.of() : templates.stream().map(template -> template.toBuilder().build()).toList();
	}

	/**
	 * 409 before anything is written: a template that goes while somebody holds one of its shifts,
	 * or an upper limit below the people already on a shift.
	 */
	private static void refuseWhatCannotChange(List<Shift> shifts, Map<String, ShiftTemplate> templates) {
		List<String> held = new ArrayList<>();
		int over = -1;
		for (Shift shift : shifts) {
			if (shift.getTemplateKey() == null) {
				continue;
			}
			ShiftTemplate template = templates.get(shift.getTemplateKey());
			if (template == null && shift.assigned() > 0) {
				held.add(shift.getId());
			}
			if (template != null && template.getMaxHelpers() != null && shift.assigned() > template.getMaxHelpers()) {
				over = Math.max(over, shift.assigned());
			}
		}
		if (!held.isEmpty()) {
			throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.seriesShiftHasAssignments",
					Map.of("shiftIds", String.join(",", held)));
		}
		if (over >= 0) {
			throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.maxBelowAssigned",
					Map.of("assigned", String.valueOf(over)));
		}
	}

	/** The carried occurrences move to the new series and its wall clock: one bulk write. */
	private void rehang(ShiftSeries series, Map<LocalDate, ShiftEvent> carried) {
		if (carried.isEmpty()) {
			return;
		}
		BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.ORDERED, ShiftEvent.class);
		Instant now = clock.instant();
		carried.forEach((day, old) -> {
			ShiftSeriesPlan.Occurrence occurrence = ShiftSeriesPlan.occurrence(series, day);
			Update update = new Update().set("seriesId", series.getId()).set("occurrenceDate", day)
					.set("detached", false).set("startsAt", occurrence.startsAt()).set("endsAt", occurrence.endsAt())
					.set("title", series.getTitle()).set("timezone", series.getTimezone())
					.set("selfAssignable", series.isSelfAssignable()).set("updatedAt", now);
			setOrUnset(update, "description", series.getDescription());
			setOrUnset(update, "location", series.getLocation());
			bulk.updateOne(Query.query(Criteria.where("_id").is(old.getId())), update);
		});
		bulk.execute();
	}

	/**
	 * The shifts of the carried occurrences, matched to the templates by key: changed in one bulk
	 * write, their assignments moved along in another, removed templates' shifts deleted (nobody
	 * holds them, {@link #refuseWhatCannotChange} saw to that). Shifts without a key stay.
	 */
	private void retime(ShiftSeries series, List<Shift> shifts, Map<String, LocalDate> dayOf,
			Map<String, ShiftTemplate> templates, Set<String> moved, Set<String> reached) {
		BulkOperations shiftBulk = mongo.bulkOps(BulkOperations.BulkMode.ORDERED, Shift.class);
		BulkOperations assignmentBulk = mongo.bulkOps(BulkOperations.BulkMode.ORDERED, ShiftAssignment.class);
		boolean shiftWrites = false;
		boolean assignmentWrites = false;
		List<String> gone = new ArrayList<>();
		List<String> opened = new ArrayList<>();
		Instant now = clock.instant();
		Instant remindAgainAfter = now.plus(ShiftRules.SHIFT_MAX);
		for (Shift shift : shifts) {
			if (shift.getTemplateKey() == null) {
				continue;
			}
			ShiftTemplate template = templates.get(shift.getTemplateKey());
			if (template == null) {
				gone.add(shift.getId());
				continue;
			}
			ShiftSeriesPlan.ShiftTimes times = ShiftSeriesPlan.shiftTimes(series, template, dayOf.get(shift.getEventId()));
			Update update = new Update().set("startsAt", times.startsAt()).set("endsAt", times.endsAt())
					.set("requiredHelpers", template.required()).set("updatedAt", now);
			setOrUnset(update, "title", template.getTitle());
			setOrUnset(update, "maxHelpers", template.getMaxHelpers());
			shiftBulk.updateOne(Query.query(Criteria.where("_id").is(shift.getId())), update);
			shiftWrites = true;
			if (ShiftService.raises(shift.getMaxHelpers(), template.getMaxHelpers())) {
				opened.add(shift.getId());
			}
			if (!times.startsAt().equals(shift.getStartsAt()) || !times.endsAt().equals(shift.getEndsAt())) {
				Update follow = new Update().set("startsAt", times.startsAt()).set("endsAt", times.endsAt());
				if (times.startsAt().isAfter(remindAgainAfter)) {
					follow.unset("reminderSentAt");
				}
				assignmentBulk.updateMulti(Query.query(Criteria.where("shiftId").is(shift.getId())), follow);
				assignmentWrites = true;
				moved.add(shift.getId());
				reached.add(shift.getId());
			}
		}
		if (shiftWrites) {
			shiftBulk.execute();
		}
		if (!opened.isEmpty()) {
			// HIN-162: a higher upper limit; whoever waits moves up after the commit.
			events.publishEvent(ShiftDomainEvents.SeatsOpened.of(opened));
		}
		if (assignmentWrites) {
			assignmentBulk.execute();
		}
		if (!gone.isEmpty()) {
			Query ofGone = Query.query(Criteria.where("_id").in(gone));
			mongo.updateMulti(ofGone, new Update().set("deleted", true), Shift.class);
			mongo.remove(Query.query(Criteria.where("shiftId").in(gone)), ShiftAssignment.class);
			mongo.remove(ofGone, Shift.class);
		}
	}

	/** What ending occurrences did. */
	record Ended(int removed, int cancelled) {
	}

	/**
	 * Ends [occurrences]: their shifts tombstoned first, so no sign-up slips in; one aggregation of
	 * the active places per occurrence and person; the occurrences nobody holds deleted together,
	 * the others (all of them with [cancelAll]) cancelled together. [counts] gets each person's lost
	 * places.
	 */
	private Ended end(List<ShiftEvent> occurrences, boolean cancelAll, Map<String, Integer> counts) {
		if (occurrences.isEmpty()) {
			return new Ended(0, 0);
		}
		List<String> ids = occurrences.stream().map(ShiftEvent::getId).toList();
		mongo.updateMulti(Query.query(Criteria.where("eventId").in(ids).and("deleted").is(false)),
				new Update().set("deleted", true), Shift.class);
		Set<String> held = new HashSet<>();
		Aggregation places = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("eventId").in(ids).and("status").in(ShiftAssignment.Status.ACTIVE)),
				Aggregation.group("eventId", "userId").count().as("places"));
		for (Document row : mongo.aggregate(places, mongo.getCollectionName(ShiftAssignment.class), Document.class)) {
			Document key = row.get("_id", Document.class);
			held.add(key.getString("eventId"));
			counts.merge(key.getString("userId"), ((Number) row.get("places")).intValue(), Integer::sum);
		}
		List<String> keep = cancelAll ? ids : ids.stream().filter(held::contains).toList();
		List<String> gone = ids.stream().filter(id -> !keep.contains(id)).toList();
		if (!gone.isEmpty()) {
			Query ofGone = Query.query(Criteria.where("eventId").in(gone));
			mongo.remove(ofGone, ShiftAssignment.class);
			mongo.remove(ofGone, Shift.class);
			mongo.remove(Query.query(Criteria.where("_id").in(gone)), ShiftEvent.class);
		}
		if (!keep.isEmpty()) {
			// HIN-162: the waiting lists of a cancelled occurrence end; their people hear it with the others.
			Query waitingOnKept = Query.query(Criteria.where("eventId").in(keep).and("status").is(ShiftAssignment.Status.WAITING));
			waitingOnKept.fields().include("userId");
			for (ShiftAssignment waiting : mongo.find(waitingOnKept, ShiftAssignment.class)) {
				counts.merge(waiting.getUserId(), 1, Integer::sum);
			}
			mongo.updateMulti(Query.query(Criteria.where("eventId").in(keep).and("status").is(ShiftAssignment.Status.WAITING)),
					new Update().set("status", ShiftAssignment.Status.RELEASED), ShiftAssignment.class);
			Query ofKept = Query.query(Criteria.where("eventId").in(keep));
			mongo.updateMulti(Query.query(Criteria.where("_id").in(keep)),
					new Update().set("cancelled", true).set("updatedAt", clock.instant()), ShiftEvent.class);
			mongo.updateMulti(ofKept, new Update().set("deleted", false).set("cancelled", true), Shift.class);
			mongo.updateMulti(ofKept, new Update().set("cancelled", true), ShiftAssignment.class);
		}
		return new Ended(gone.size(), keep.size());
	}

	/** Adds each person's active places on the shifts [selected] to [counts]. */
	private void count(Criteria selected, Map<String, Integer> counts) {
		Query query = Query.query(selected.and("status").in(ShiftAssignment.Status.ACTIVE));
		query.fields().include("userId");
		for (ShiftAssignment assignment : mongo.find(query, ShiftAssignment.class)) {
			counts.merge(assignment.getUserId(), 1, Integer::sum);
		}
	}

	private void notifyChanged(String projectId, User caller, Map<String, Integer> counts) {
		Map<String, Integer> others = new LinkedHashMap<>(counts);
		others.remove(caller.getId());
		if (!others.isEmpty()) {
			events.publishEvent(new ShiftDomainEvents.SeriesChanged(projectId, caller.getId(), others));
		}
	}

	private List<Shift> liveShifts(String eventId) {
		return mongo.find(Query.query(Criteria.where("eventId").is(eventId).and("deleted").is(false))
				.with(Sort.by("startsAt", "_id")), Shift.class);
	}

	private Set<String> existingProjects(Set<String> ids) {
		List<Object> stored = ids.stream().filter(Objects::nonNull)
				.map(id -> org.bson.types.ObjectId.isValid(id) ? (Object) new org.bson.types.ObjectId(id) : id).toList();
		Query query = Query.query(Criteria.where("_id").in(stored));
		query.fields().include("_id");
		return mongo.find(query, Document.class, mongo.getCollectionName(Project.class)).stream()
				.map(project -> String.valueOf(project.get("_id"))).collect(Collectors.toSet());
	}

	private ShiftSeries fromDraft(User caller, String projectId, SeriesDraft draft) {
		String zone = draft.timezone() == null || draft.timezone().isBlank()
				? UserZones.of(caller, serverSettings.get()).getId()
				: UserZones.normalize(draft.timezone());
		List<ShiftTemplate> templates = new ArrayList<>();
		if (draft.shifts() != null) {
			for (TemplateDraft template : draft.shifts()) {
				templates.add(template == null ? null : ShiftTemplate.builder()
						.key(template.key() == null ? null : template.key().strip())
						.title(template.title())
						.dayOffset(template.dayOffset() == null ? 0 : template.dayOffset())
						.start(template.start()).end(template.end())
						.requiredHelpers(template.requiredHelpers())
						.maxHelpers(template.maxHelpers())
						.build());
			}
		}
		return ShiftSeries.builder()
				.projectId(projectId)
				.title(draft.title())
				.description(draft.description())
				.location(draft.location())
				.timezone(zone)
				.firstStart(draft.firstStart())
				.durationMinutes(draft.durationMinutes())
				.rrule(draft.rrule() == null ? null : draft.rrule().strip())
				.exdates(new ArrayList<>())
				.shifts(templates)
				.selfAssignable(draft.selfAssignable() == null || draft.selfAssignable())
				.finished(false)
				.createdBy(caller.getId())
				.build();
	}

	private static String normalizedZone(String requested, String fallback) {
		String zone = UserZones.normalize(requested);
		return zone == null ? fallback : zone;
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text;
	}

	LocalDate today(ShiftSeries series) {
		return ShiftSeriesPlan.today(clock.instant(), series.getTimezone());
	}

	private void audited(AuditAction action, User actor, String targetId, Map<String, String> meta) {
		events.publishEvent(new ShiftDomainEvents.Audited(action, actor.getId(), ShiftDomainEvents.label(actor),
				targetId, meta));
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
