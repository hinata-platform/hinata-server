package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.export.ExportBlock;
import com.ahmadre.hinata.issue.export.ExportDocument;
import com.ahmadre.hinata.issue.export.ExportDocumentRenderer;
import com.ahmadre.hinata.issue.export.ExportFonts;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportRateLimiter;
import com.ahmadre.hinata.issue.export.ExportSlots;
import com.ahmadre.hinata.issue.export.ExportText;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * An event's shift plan as a file (HIN-163): a PDF for the notice board at the entrance, a Word
 * document for whoever edits it further, a workbook for the office.
 *
 * <p>Nothing here lays anything out. The plan is an {@link ExportDocument}, rendered by the same
 * renderers as an issue and a time report: the event's head (period in the event's zone, place,
 * places taken, series), then one section per day with a table of its shifts, read from a cursor in
 * chunks of {@link #CHUNK} and never held whole.
 *
 * <p>Names are the exception, not the rule. Without {@code names} the file holds numbers only and
 * no person is read at all; with them, only somebody who plans the project may ask (403 otherwise),
 * because a file with names has left the access control for good. Only places held
 * ({@code ASSIGNED}, {@code CONFIRMED}) are named, never the waiting list, at most
 * {@link #MAX_NAMES_PER_SHIFT} per shift.
 *
 * <p>The checks run in the order of {@code TimeReportExport}: the event and the right to it (a 404
 * or a 403 costs nothing), a slot among the instance's {@link ExportSlots}, then the caller's
 * budget ({@link ExportRateLimiter}). Each file is audited ({@link AuditAction#SHIFT_PLAN_EXPORTED})
 * with the event id, the format and whether it named people, never the title.
 */
@Slf4j
@Service
public class ShiftPlanExport {

	/** Shifts each format holds at most; a Word document is built in memory, so it holds fewest. */
	static final Map<ExportFormat, Integer> MAX_ROWS = Map.of(ExportFormat.PDF, 2_000, ExportFormat.DOCX, 1_000,
			ExportFormat.XLSX, 10_000);

	/** Shifts read, and their people named, per round trip. */
	static final int CHUNK = 200;

	/** People named on one shift; the rest is one line saying how many. */
	static final int MAX_NAMES_PER_SHIFT = 20;

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final MongoTemplate mongo;
	private final ShiftAccess access;
	private final ShiftSettings settings;
	private final ShiftSeriesLabels labels;
	private final ShiftDelivery delivery;
	private final ExportSlots slots;
	private final ExportRateLimiter limiter;
	private final MessageSource messages;
	private final SettingsService serverSettings;
	private final BrandLogoService brandLogo;
	private final Clock clock;
	private final Map<ExportFormat, ExportDocumentRenderer> renderers;

	public ShiftPlanExport(MongoTemplate mongo, ShiftAccess access, ShiftSettings settings, ShiftSeriesLabels labels,
			ShiftDelivery delivery, ExportSlots slots, ExportRateLimiter limiter, MessageSource messages,
			SettingsService serverSettings, BrandLogoService brandLogo, Clock clock,
			List<ExportDocumentRenderer> renderers) {
		this.mongo = mongo;
		this.access = access;
		this.settings = settings;
		this.labels = labels;
		this.delivery = delivery;
		this.slots = slots;
		this.limiter = limiter;
		this.messages = messages;
		this.serverSettings = serverSettings;
		this.brandLogo = brandLogo;
		this.clock = clock;
		Map<ExportFormat, ExportDocumentRenderer> byFormat = new EnumMap<>(ExportFormat.class);
		renderers.forEach(renderer -> byFormat.put(renderer.format(), renderer));
		this.renderers = Map.copyOf(byFormat);
	}

	/** One day of the event and its numbers, in the event's zone. */
	record Day(LocalDate date, int shifts) {
	}

	/**
	 * A file about to be written: the event, the days, the words, the name. Holds an export slot
	 * until it is closed.
	 */
	public record Plan(User caller, ShiftEvent event, ExportFormat format, boolean names, ExportWords words,
			List<Day> days, int placesRequired, int placesTaken, int maxRows, boolean truncated, String fileName,
			ExportSlots.Slot slot) implements AutoCloseable {

		@Override
		public void close() {
			slot.close();
		}
	}

	/**
	 * Checks the event and the right to the file, takes a slot and the caller's budget, and counts.
	 * [asked] is the language the app asked in.
	 *
	 * @throws ApiException 404 for an event out of sight, 403 {@code error.shifts.notPlanner} for
	 *                      names asked by somebody who does not plan, 429 when the instance is busy
	 *                      or the caller over budget
	 */
	public Plan plan(User caller, String eventId, ExportFormat format, boolean names, Locale asked) {
		settings.requireEnabled();
		ShiftAccess.Scoped<ShiftEvent> scoped = access.visibleEvent(eventId, caller);
		if (names) {
			access.requirePlanner(scoped.project(), caller);
		}
		ExportSlots.Slot slot = slots.tryAcquire();
		try {
			limiter.require(caller.getId());
			ShiftEvent event = scoped.item();
			ZoneId zone = ShiftRules.zoneOf(event.getTimezone());
			ExportWords words = new ExportWords(messages, locale(format, asked), zone);
			int max = MAX_ROWS.get(format);
			List<Day> all = days(event.getId(), zone);
			List<Day> kept = new ArrayList<>();
			int shifts = 0;
			int counted = 0;
			for (Day day : all) {
				shifts += day.shifts();
				if (counted < max) {
					kept.add(day);
					counted += day.shifts();
				}
			}
			int[] places = places(event.getId());
			String fileName = words.t("export.shiftPlan.fileName") + "-" + ExportText.slug(event.getTitle(), "event")
					+ "-" + FILE_DAY.format(event.getStartsAt().atZone(zone)) + "." + format.extension();
			return new Plan(caller, event, format, names, words, kept, places[0], places[1], max, shifts > max,
					fileName, slot);
		}
		catch (RuntimeException refused) {
			slot.close();
			throw refused;
		}
	}

	/** Writes the plan to [out] in its format and audits it. */
	public void write(Plan plan, OutputStream out) {
		ExportDocumentRenderer renderer = renderers.get(plan.format());
		if (renderer == null) {
			throw ApiException.badRequest("error.issue.exportFailed");
		}
		try (Stream<Shift> stream = mongo.stream(Query.query(live(plan.event().getId()))
				.with(Sort.by("startsAt", "_id")).limit(plan.maxRows()).cursorBatchSize(CHUNK), Shift.class)) {
			renderer.render(document(plan, new Rows(stream.iterator(), plan)), out);
		}
		delivery.audit(new ShiftDomainEvents.Audited(AuditAction.SHIFT_PLAN_EXPORTED, plan.caller().getId(),
				ShiftDomainEvents.label(plan.caller()), plan.event().getId(), Map.of(
						"eventId", plan.event().getId(),
						"format", plan.format().extension(),
						"names", String.valueOf(plan.names()))));
	}

	private ExportDocument document(Plan plan, Rows rows) {
		ExportWords words = plan.words();
		ShiftEvent event = plan.event();
		List<ExportBlock> blocks = new ArrayList<>();
		List<ExportBlock.KeyValue> head = new ArrayList<>();
		head.add(new ExportBlock.KeyValue(words.t("export.shiftPlan.period"),
				words.instant(event.getStartsAt()) + " – " + words.instant(event.getEndsAt())));
		if (event.getLocation() != null && !event.getLocation().isBlank()) {
			head.add(new ExportBlock.KeyValue(words.t("export.shiftPlan.location"), event.getLocation().trim()));
		}
		head.add(new ExportBlock.KeyValue(words.t("export.shiftPlan.staffing"),
				words.t("export.shiftPlan.places", plan.placesTaken(), plan.placesRequired())));
		String series = seriesLabel(event, words.locale());
		if (series != null) {
			head.add(new ExportBlock.KeyValue(words.t("export.shiftPlan.series"), series));
		}
		if (event.isCancelled()) {
			head.add(new ExportBlock.KeyValue(words.t("export.shiftPlan.status"),
					words.t("export.shiftPlan.cancelled")));
		}
		blocks.add(new ExportBlock.KeyValues(head));

		List<String> headers = new ArrayList<>(List.of(words.t("export.shiftPlan.column.time"),
				words.t("export.shiftPlan.column.shift"), words.t("export.shiftPlan.column.required"),
				words.t("export.shiftPlan.column.assigned")));
		List<Float> widths = new ArrayList<>(List.of(1.2f, 2f, 0.8f, 1.2f));
		if (plan.names()) {
			headers.add(words.t("export.shiftPlan.column.people"));
			widths.add(3.5f);
		}
		if (plan.days().isEmpty()) {
			blocks.add(new ExportBlock.Note(words.t("export.shiftPlan.empty")));
		}
		for (int i = 0; i < plan.days().size(); i++) {
			LocalDate day = plan.days().get(i).date();
			boolean last = i == plan.days().size() - 1;
			blocks.add(new ExportBlock.Section(words.date(day)));
			blocks.add(new ExportBlock.LongTable(List.copyOf(headers), rows.until(last ? null : day),
					List.copyOf(widths), Set.of(2)));
		}
		if (plan.truncated()) {
			blocks.add(new ExportBlock.Note(words.t("export.shiftPlan.truncated", plan.maxRows())));
		}
		Instant now = clock.instant();
		blocks.add(new ExportBlock.Note(words.t("export.shiftPlan.asOf", words.instant(now))));
		return new ExportDocument(words.t("export.shiftPlan.eyebrow"), event.getTitle(), "", blocks, organization(),
				logo(), now, words);
	}

	/**
	 * The shifts as rows, read in chunks: each chunk's people are looked up together, then its rows
	 * handed out one by one. Each day's table takes the rows up to and including its day, so the
	 * one cursor under all of them is walked once, in order.
	 */
	private final class Rows {

		private final Iterator<Shift> source;
		private final Plan plan;
		private final ZoneId zone;
		private final Deque<Row> buffer = new ArrayDeque<>();

		Rows(Iterator<Shift> source, Plan plan) {
			this.source = source;
			this.plan = plan;
			this.zone = plan.words().zone();
		}

		/** The rows of days up to [day], or every row left for null. Walkable once. */
		Iterable<List<String>> until(LocalDate day) {
			return () -> new Iterator<>() {
				@Override
				public boolean hasNext() {
					fill();
					return !buffer.isEmpty() && (day == null || !buffer.peekFirst().day().isAfter(day));
				}

				@Override
				public List<String> next() {
					if (!hasNext()) {
						throw new NoSuchElementException();
					}
					return buffer.removeFirst().cells();
				}
			};
		}

		private void fill() {
			if (!buffer.isEmpty() || !source.hasNext()) {
				return;
			}
			List<Shift> chunk = new ArrayList<>(CHUNK);
			while (chunk.size() < CHUNK && source.hasNext()) {
				chunk.add(source.next());
			}
			Map<String, List<String>> people = plan.names() ? people(chunk) : Map.of();
			for (Shift shift : chunk) {
				buffer.add(new Row(shift.getStartsAt().atZone(zone).toLocalDate(),
						row(shift, people.get(shift.getId()), plan)));
			}
		}
	}

	private record Row(LocalDate day, List<String> cells) {
	}

	private List<String> row(Shift shift, List<String> people, Plan plan) {
		ExportWords words = plan.words();
		int missing = ShiftRules.shortOfPeople(shift) ? shift.required() - shift.assigned() : 0;
		List<String> cells = new ArrayList<>(5);
		cells.add(time(shift, words));
		cells.add(shift.getTitle() == null || shift.getTitle().isBlank()
				? words.t("export.shiftPlan.untitled") : shift.getTitle().trim());
		cells.add(String.valueOf(shift.required()));
		cells.add(missing > 0 ? words.t("export.shiftPlan.short", shift.assigned(), missing)
				: String.valueOf(shift.assigned()));
		if (plan.names()) {
			cells.add(names(people == null ? List.of() : people, shift.assigned(), words));
		}
		return cells;
	}

	/** Start and end on the wall clock; an end on another day names that day. */
	private static String time(Shift shift, ExportWords words) {
		ZonedDateTime start = shift.getStartsAt().atZone(words.zone());
		ZonedDateTime end = shift.getEndsAt().atZone(words.zone());
		String until = end.toLocalDate().equals(start.toLocalDate()) ? TIME.format(end)
				: words.date(end.toLocalDate()) + " " + TIME.format(end);
		return TIME.format(start) + " – " + until;
	}

	/**
	 * Up to {@link #MAX_NAMES_PER_SHIFT} names, then how many more hold a place. [assigned] is the
	 * shift's counter, which counts places held and never the waiting list.
	 */
	private static String names(List<String> people, int assigned, ExportWords words) {
		List<String> shown = people.size() > MAX_NAMES_PER_SHIFT ? people.subList(0, MAX_NAMES_PER_SHIFT) : people;
		String joined = String.join(", ", shown);
		int more = Math.max(assigned, people.size()) - shown.size();
		return more > 0 ? words.t("export.shiftPlan.more", joined, more) : joined;
	}

	/**
	 * The names of the people holding a place on each shift of [chunk], in the order they took it:
	 * one read of the assignments ({@code shift_status}), one of the accounts, only their names.
	 */
	private Map<String, List<String>> people(Collection<Shift> chunk) {
		Query held = Query.query(Criteria.where("shiftId").in(chunk.stream().map(Shift::getId).toList())
				.and("status").in(ShiftAssignment.Status.ACTIVE)).with(Sort.by("createdAt", "_id"));
		held.fields().include("shiftId", "userId");
		Map<String, List<String>> idsByShift = new LinkedHashMap<>();
		for (ShiftAssignment assignment : mongo.find(held, ShiftAssignment.class)) {
			idsByShift.computeIfAbsent(assignment.getShiftId(), key -> new ArrayList<>()).add(assignment.getUserId());
		}
		Set<Object> ids = new HashSet<>();
		idsByShift.values().forEach(list -> list.forEach(raw -> ids.add(ObjectId.isValid(raw) ? new ObjectId(raw) : raw)));
		Map<String, String> nameOf = new HashMap<>();
		if (!ids.isEmpty()) {
			Query accounts = Query.query(Criteria.where("_id").in(ids));
			accounts.fields().include("displayName", "username");
			for (Document user : mongo.find(accounts, Document.class, mongo.getCollectionName(User.class))) {
				String name = user.getString("displayName");
				nameOf.put(String.valueOf(user.get("_id")),
						name != null && !name.isBlank() ? name.trim() : user.getString("username"));
			}
		}
		Map<String, List<String>> out = new HashMap<>();
		idsByShift.forEach((shiftId, userIds) -> out.put(shiftId,
				userIds.stream().map(nameOf::get).filter(Objects::nonNull).toList()));
		return out;
	}

	/**
	 * The days the event's live shifts start on, in the event's zone, each with how many: one
	 * aggregation, a row per day.
	 */
	private List<Day> days(String eventId, ZoneId zone) {
		AggregationOperation group = context -> new Document("$group", new Document("_id",
				new Document("$dateToString", new Document("format", "%Y-%m-%d").append("date", "$startsAt")
						.append("timezone", zone.getId())))
				.append("shifts", new Document("$sum", 1)));
		Aggregation aggregation = Aggregation.newAggregation(Aggregation.match(live(eventId)), group,
				Aggregation.sort(Sort.by("_id")));
		List<Day> out = new ArrayList<>();
		for (Document row : mongo.aggregate(aggregation, mongo.getCollectionName(Shift.class), Document.class)) {
			out.add(new Day(LocalDate.parse(row.getString("_id")), ((Number) row.get("shifts")).intValue()));
		}
		return out;
	}

	/**
	 * Places needed and taken across the event's live shifts, as the event list counts them: a shift
	 * over its need counts as met, not as more.
	 */
	private int[] places(String eventId) {
		AggregationOperation group = context -> new Document("$group", new Document("_id", null)
				.append("required", new Document("$sum", "$requiredHelpers"))
				.append("taken", new Document("$sum", ShiftRules.placesTakenExpression())));
		Aggregation aggregation = Aggregation.newAggregation(Aggregation.match(live(eventId)), group);
		Document row = mongo.aggregate(aggregation, mongo.getCollectionName(Shift.class), Document.class)
				.getUniqueMappedResult();
		if (row == null) {
			return new int[] {0, 0};
		}
		return new int[] {((Number) row.get("required")).intValue(), ((Number) row.get("taken")).intValue()};
	}

	private static Criteria live(String eventId) {
		return Criteria.where("eventId").is(eventId).and("deleted").is(false);
	}

	/** The series sentence in the file's language (HIN-161), or null for an event of its own. */
	private String seriesLabel(ShiftEvent event, Locale locale) {
		if (event.getSeriesId() == null) {
			return null;
		}
		Query query = Query.query(Criteria.where("_id").is(event.getSeriesId()));
		query.fields().include("rrule", "firstStart");
		ShiftSeries series = mongo.findOne(query, ShiftSeries.class);
		return series == null ? null : labels.label(locale, series.getRrule(), series.getFirstStart());
	}

	/**
	 * The language of the file: the one the app asked in, and for a PDF English where the page
	 * cannot draw that language's script, as the time report does.
	 */
	private Locale locale(ExportFormat format, Locale asked) {
		return format == ExportFormat.PDF
				? ExportFonts.renderableLocale(asked, messages.getMessage("export.shiftPlan.eyebrow", null, "", asked))
				: asked;
	}

	private String organization() {
		try {
			String name = serverSettings.get().getOrganizationName();
			return name == null ? "" : name.trim();
		}
		catch (RuntimeException ex) {
			return "";
		}
	}

	/** The letterhead's mark, or none: a logo that cannot be read costs the letterhead, not the file. */
	private byte[] logo() {
		try {
			return brandLogo.raster().orElse(null);
		}
		catch (RuntimeException ex) {
			log.warn("The organization logo was left out of a shift plan: {}", ex.toString());
			return null;
		}
	}
}
