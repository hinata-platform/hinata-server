package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.export.ExportRateLimiter;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Time entries from a CSV file (HIN-93), in two steps: a preview that checks every row the way a
 * typed entry is checked and writes nothing, then a commit that writes the rows that passed.
 *
 * <p>Every row goes through {@link TimeTrackingService#checkImported}: placement against the
 * owner's reach, lock date, approvals, the required fields and the tag catalogue. An import is
 * nobody's side door into a frozen month. Somebody imports their own entries; an administrator
 * may import for others, and that is audited ({@link AuditAction#TIME_ENTRIES_IMPORTED}).
 *
 * <p>A commit is all or nothing without a transaction: rows are written in parts of
 * {@link #CHUNK}, each entry marked with the import's id, and a failure takes every marked entry
 * back ({@link TimeTrackingService#withdrawImported}). A commit the process never finished — a
 * restart half way — is taken back by the hourly sweep ({@link #sweep}). Committing twice writes
 * once: the first commit claims the import, the second reads its result.
 */
@Service
@RequiredArgsConstructor
public class TimeImportService {

	static final long MAX_BYTES = 5L * 1024 * 1024;
	static final int MAX_ROWS = 10_000;
	static final int PREVIEW_ROWS = 50;
	static final int CHUNK = 500;
	static final int ERRORS_PAGE_MAX = 100;
	static final Duration PENDING_KEPT = Duration.ofDays(1);
	static final Duration FINISHED_KEPT = Duration.ofDays(7);
	static final Duration STALE_COMMIT = Duration.ofHours(1);

	private static final Pattern NOT_A_LETTER = Pattern.compile("[^\\p{L}\\p{N}]+");
	private static final Set<String> TRUE = Set.of("true", "yes", "ja", "y", "j", "1", "x", "oui", "sí", "si", "да");
	private static final Set<String> FALSE = Set.of("false", "no", "nein", "n", "0", "non", "нет");

	private final MongoTemplate mongo;
	private final TimeTrackingService entries;
	private final UserRepository users;
	private final ProjectReach reach;
	private final ExportRateLimiter limiter;
	private final AuditService audit;
	private final MessageSource messages;
	private final Clock clock;

	/** What a column of the file holds. */
	public enum Column {
		DATE, START, END, MINUTES, HOURS, DURATION, PROJECT, ISSUE, ACTIVITY, DESCRIPTION, TAGS, BILLABLE, USER
	}

	/**
	 * The header words each column is recognised by, normalised to letters and digits, most
	 * specific first: this platform's own exports in its languages, toggl's, and the plain words.
	 */
	static final Map<Column, List<String>> SYNONYMS = synonyms();

	/**
	 * @param rows   the first {@link #PREVIEW_ROWS} rows as they were read, each with its error
	 * @param errors the first page of every row that did not pass
	 */
	public record Preview(String importId, List<String> headers, Map<Column, Integer> mapping, int totalRows,
			int validRows, int errorCount, List<PreviewRow> rows, Page<TimeImport.RowError> errors) {
	}

	public record PreviewRow(int line, LocalDate date, Integer minutes, String project, String issue,
			String description, List<String> tags, String error) {
	}

	public record Result(String importId, TimeImport.Status status, int inserted) {
	}

	// --- preview ------------------------------------------------------------

	/**
	 * Reads [file] and checks every row for [targetUserId] (the uploader when blank), with
	 * [mapping] where the caller gave one and the header's words where not. Replaces the
	 * uploader's previous preview.
	 */
	public Preview preview(User actor, MultipartFile file, Map<Column, Integer> mapping, String targetUserId,
			Locale locale) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("error.time.import.empty");
		}
		if (file.getSize() > MAX_BYTES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "error.time.import.tooLarge", MAX_BYTES / 1024 / 1024);
		}
		User target = target(actor, targetUserId);
		limiter.require(actor.getId());

		List<String> headers;
		List<List<String>> records = new ArrayList<>();
		try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
			TimeCsvReader csv = new TimeCsvReader(reader);
			headers = csv.header();
			if (headers.isEmpty()) {
				throw ApiException.badRequest("error.time.import.empty");
			}
			List<String> record;
			while ((record = csv.next()) != null) {
				if (records.size() == MAX_ROWS) {
					throw ApiException.badRequest("error.time.import.tooManyRows", MAX_ROWS);
				}
				records.add(record);
			}
		}
		catch (IOException ex) {
			throw ApiException.badRequest("error.time.import.unreadable");
		}
		Map<Column, Integer> columns = mapping == null || mapping.isEmpty() ? detect(headers) : checked(mapping, headers);

		Rows rows = new Rows(actor, target, columns);
		List<TimeImport.Row> valid = new ArrayList<>();
		List<TimeImport.RowError> errors = new ArrayList<>();
		List<PreviewRow> shown = new ArrayList<>();
		for (int i = 0; i < records.size(); i++) {
			int line = i + 2;
			List<String> record = records.get(i);
			String error = null;
			TimeImport.Row row = null;
			try {
				row = rows.check(record, line);
				valid.add(row);
			}
			catch (ApiException refused) {
				error = messages.getMessage(refused.getMessageKey(), refused.getArgs(), refused.getMessageKey(),
						locale);
				errors.add(new TimeImport.RowError(line, error));
			}
			if (shown.size() < PREVIEW_ROWS) {
				shown.add(row != null
						? new PreviewRow(line, row.getDate(), row.getMinutes(), rows.projectLabel(row.getProjectId()),
								value(record, columns.get(Column.ISSUE)), row.getDescription(), row.getTags(), null)
						: new PreviewRow(line, null, null, value(record, columns.get(Column.PROJECT)),
								value(record, columns.get(Column.ISSUE)), value(record, columns.get(Column.DESCRIPTION)),
								List.of(), error));
			}
		}

		mongo.remove(Query.query(Criteria.where("ownerId").is(actor.getId()).and("status")
				.is(TimeImport.Status.PENDING)), TimeImport.class);
		Instant now = clock.instant();
		TimeImport saved = mongo.insert(TimeImport.builder()
				.ownerId(actor.getId())
				.targetUserId(target.getId())
				.status(TimeImport.Status.PENDING)
				.fileName(file.getOriginalFilename() == null ? null
						: file.getOriginalFilename().substring(0, Math.min(200, file.getOriginalFilename().length())))
				.totalRows(records.size())
				.createdAt(now)
				.rows(valid)
				.errors(errors)
				.expiresAt(now.plus(PENDING_KEPT))
				.build());
		Pageable first = PageRequest.of(0, 20);
		return new Preview(saved.getId(), headers, columns, records.size(), valid.size(), errors.size(), shown,
				new PageImpl<>(errors.subList(0, Math.min(errors.size(), first.getPageSize())), first, errors.size()));
	}

	/** One page of the rows of an import that did not pass. */
	public Page<TimeImport.RowError> errors(User actor, String importId, int page, int size) {
		TimeImport found = own(actor, importId);
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, MAX_ROWS), Math.clamp(size, 1, ERRORS_PAGE_MAX));
		List<TimeImport.RowError> all = found.getErrors();
		int from = (int) Math.min(pageable.getOffset(), all.size());
		int to = Math.min(from + pageable.getPageSize(), all.size());
		return new PageImpl<>(all.subList(from, to), pageable, all.size());
	}

	/** Throws away a preview nobody wants to commit. */
	public void discard(User actor, String importId) {
		mongo.remove(Query.query(Criteria.where("_id").is(importId).and("ownerId").is(actor.getId())
				.and("status").is(TimeImport.Status.PENDING)), TimeImport.class);
	}

	// --- commit -------------------------------------------------------------

	/** Writes the rows that passed, all of them or none; a second commit reads the first one's result. */
	public Result commit(User actor, String importId) {
		Instant started = clock.instant();
		TimeImport claimed = mongo.findAndModify(
				Query.query(Criteria.where("_id").is(importId).and("ownerId").is(actor.getId())
						.and("status").is(TimeImport.Status.PENDING)),
				new Update().set("status", TimeImport.Status.COMMITTING).set("commitStartedAt", started),
				FindAndModifyOptions.options().returnNew(true), TimeImport.class);
		if (claimed == null) {
			TimeImport found = own(actor, importId);
			return switch (found.getStatus()) {
				case COMMITTED -> new Result(found.getId(), found.getStatus(), found.getInserted());
				case COMMITTING -> throw ApiException.conflict("error.time.import.inProgress");
				default -> throw ApiException.conflict("error.time.import.failed");
			};
		}
		Map<String, User> owners = new HashMap<>();
		Map<String, TimeTrackingService.Placement> placements = new HashMap<>();
		int written = 0;
		try {
			List<TimeImport.Row> rows = claimed.getRows();
			for (int from = 0; from < rows.size(); from += CHUNK) {
				List<WorkItem> part = new ArrayList<>(CHUNK);
				for (TimeImport.Row row : rows.subList(from, Math.min(from + CHUNK, rows.size()))) {
					User owner = owners.computeIfAbsent(row.getUserId(),
							id -> users.findById(id).orElseThrow(() -> ApiException.notFound("user")));
					try {
						// Checked again: a lock date or an approval may have moved since the preview.
						part.add(entries.checkImported(draftOf(row), owner, actor, placements));
					}
					catch (ApiException changed) {
						throw ApiException.conflict("error.time.import.rowChanged", row.getLine());
					}
				}
				entries.fileImported(part, claimed.getId(), actor);
				written += part.size();
			}
		}
		catch (RuntimeException failure) {
			entries.withdrawImported(claimed.getId());
			finish(claimed.getId(), TimeImport.Status.FAILED, 0,
					failure instanceof ApiException api ? api.getMessageKey() : "error.time.import.failed");
			throw failure instanceof ApiException api ? api : ApiException.conflict("error.time.import.failed");
		}
		finish(claimed.getId(), TimeImport.Status.COMMITTED, written, null);
		if (!claimed.getTargetUserId().equals(actor.getId()) && written > 0) {
			User target = owners.get(claimed.getTargetUserId());
			AuditService.Entry entry = audit.event(AuditAction.TIME_ENTRIES_IMPORTED).actor(actor)
					.meta("import", claimed.getId())
					.meta("rows", String.valueOf(written));
			if (target != null) {
				entry.target(target);
			}
			entry.log();
		}
		return new Result(claimed.getId(), TimeImport.Status.COMMITTED, written);
	}

	/**
	 * Takes back every commit that was started and never finished, and marks it failed. The
	 * entries it wrote so far carry its id, so nothing half imported stays behind a restart.
	 */
	public int sweep() {
		Instant stale = clock.instant().minus(STALE_COMMIT);
		List<TimeImport> stuck = mongo.find(Query.query(Criteria.where("status").is(TimeImport.Status.COMMITTING)
				.and("commitStartedAt").lt(stale)).limit(100), TimeImport.class);
		for (TimeImport found : stuck) {
			entries.withdrawImported(found.getId());
			finish(found.getId(), TimeImport.Status.FAILED, 0, "error.time.import.interrupted");
		}
		return stuck.size();
	}

	private void finish(String importId, TimeImport.Status status, int inserted, String failure) {
		Instant now = clock.instant();
		mongo.updateFirst(Query.query(Criteria.where("_id").is(importId)),
				new Update().set("status", status).set("inserted", inserted).set("finishedAt", now)
						.set("failure", failure).set("rows", List.of()).set("expiresAt", now.plus(FINISHED_KEPT)),
				TimeImport.class);
	}

	private TimeImport own(User actor, String importId) {
		TimeImport found = mongo.findById(importId, TimeImport.class);
		if (found == null || !found.getOwnerId().equals(actor.getId())) {
			throw ApiException.notFound("timeImport");
		}
		return found;
	}

	/** Oneself, or — for an administrator — anybody. */
	private User target(User actor, String targetUserId) {
		if (targetUserId == null || targetUserId.isBlank() || targetUserId.equals(actor.getId())) {
			return actor;
		}
		if (!actor.isAdmin()) {
			throw ApiException.forbidden("error.time.import.othersAdminOnly");
		}
		return users.findById(targetUserId).orElseThrow(() -> ApiException.notFound("user"));
	}

	private static TimeTrackingService.NewEntry draftOf(TimeImport.Row row) {
		return new TimeTrackingService.NewEntry(row.getProjectId(), row.getIssueId(), row.getMinutes(),
				row.getDate(), row.getActivityType(), row.getDescription(), row.getStartedAt(), row.getEndedAt(),
				row.getTags(), row.isBillable());
	}

	// --- reading rows ---------------------------------------------------------

	/** The columns the header's words name; a column nobody recognised stays unmapped. */
	static Map<Column, Integer> detect(List<String> headers) {
		List<String> normalized = headers.stream().map(TimeImportService::normalize).toList();
		Map<Column, Integer> found = new EnumMap<>(Column.class);
		Set<Integer> taken = new java.util.HashSet<>();
		for (Column column : Column.values()) {
			for (String word : SYNONYMS.get(column)) {
				int index = normalized.indexOf(word);
				if (index >= 0 && !taken.contains(index)) {
					found.put(column, index);
					taken.add(index);
					break;
				}
			}
		}
		return found;
	}

	private static Map<Column, Integer> checked(Map<Column, Integer> mapping, List<String> headers) {
		Map<Column, Integer> checked = new EnumMap<>(Column.class);
		mapping.forEach((column, index) -> {
			if (index != null && index >= 0) {
				if (index >= headers.size()) {
					throw ApiException.badRequest("error.time.import.mapping");
				}
				checked.put(column, index);
			}
		});
		return checked;
	}

	/** The rows of one file: the people, projects and placements it names, looked up once each. */
	private final class Rows {

		private final User actor;
		private final User target;
		private final Map<Column, Integer> columns;
		private final Map<String, User> people = new HashMap<>();
		private final Map<String, Map<String, String>> projectsByOwner = new HashMap<>();
		private final Map<String, String> projectLabels = new HashMap<>();
		private final Map<String, TimeTrackingService.Placement> placements = new HashMap<>();

		Rows(User actor, User target, Map<Column, Integer> columns) {
			this.actor = actor;
			this.target = target;
			this.columns = columns;
		}

		TimeImport.Row check(List<String> record, int line) {
			User owner = owner(value(record, columns.get(Column.USER)));
			String project = project(value(record, columns.get(Column.PROJECT)), owner);
			ZoneId zone = entries.zoneOf(owner);
			LocalDate date = date(value(record, columns.get(Column.DATE)));
			Instant start = instant(value(record, columns.get(Column.START)), date, zone);
			Instant end = instant(value(record, columns.get(Column.END)), date, zone);
			if (start != null && end != null && end.isBefore(start)) {
				// A time of day past midnight: the shift ended the next morning.
				end = end.plus(Duration.ofDays(1));
			}
			if (date == null && start != null) {
				date = LocalDate.ofInstant(start, zone);
			}
			if (date == null) {
				throw ApiException.badRequest("error.time.import.date");
			}
			Integer minutes = start != null && end != null ? null : minutes(record);
			if (minutes == null && (start == null || end == null)) {
				throw ApiException.badRequest("error.time.import.duration");
			}
			String issue = blankToNull(value(record, columns.get(Column.ISSUE)));
			TimeTrackingService.NewEntry draft = new TimeTrackingService.NewEntry(project, issue, minutes, date,
					blankToNull(value(record, columns.get(Column.ACTIVITY))),
					blankToNull(value(record, columns.get(Column.DESCRIPTION))), start, end,
					tags(value(record, columns.get(Column.TAGS))), billable(value(record, columns.get(Column.BILLABLE))));
			WorkItem item = entries.checkImported(draft, owner, actor, placements);
			return TimeImport.Row.builder().line(line).userId(owner.getId()).projectId(item.getProjectId())
					.issueId(item.getIssueId()).date(item.getDate()).minutes(item.getDurationMinutes())
					.startedAt(item.getStartedAt()).endedAt(item.getEndedAt()).activityType(item.getActivityType())
					.description(item.getDescription()).tags(item.getTags()).billable(item.isBillable()).build();
		}

		String projectLabel(String projectId) {
			return projectId == null ? null : projectLabels.get(projectId);
		}

		/**
		 * Whose row it is. Only an administrator's file may name somebody else; everybody else's
		 * rows are their own, and a row naming another person is refused rather than quietly
		 * reassigned.
		 */
		private User owner(String named) {
			if (named == null || named.isBlank()) {
				return target;
			}
			String key = named.strip().toLowerCase(Locale.ROOT);
			if (!actor.isAdmin()) {
				if (key.equals(lower(actor.getUsername())) || key.equals(lower(actor.getEmail()))) {
					return actor;
				}
				throw ApiException.forbidden("error.time.import.othersAdminOnly");
			}
			return people.computeIfAbsent(key, k -> users.findByUsernameIgnoreCase(k)
					.or(() -> users.findByEmailIgnoreCase(k))
					.orElseThrow(() -> ApiException.badRequest("error.time.import.user", named)));
		}

		/** A project by key, name or id, among the projects its owner can see. */
		private String project(String named, User owner) {
			if (named == null || named.isBlank()) {
				return null;
			}
			Map<String, String> visible = projectsByOwner.computeIfAbsent(owner.getId(), id -> projectsOf(owner));
			String found = visible.get(named.strip().toLowerCase(Locale.ROOT));
			if (found == null) {
				throw ApiException.badRequest("error.time.import.project", named);
			}
			return found;
		}

		private Map<String, String> projectsOf(User owner) {
			Criteria criteria = owner.isAdmin() ? new Criteria()
					: new Criteria().orOperator(Criteria.where("memberIds").is(owner.getId()),
							Criteria.where("_id").in(reach.teamGrantedProjectIds(owner)));
			Query query = Query.query(criteria).limit(TimeReportScope.MAX_PROJECTS);
			query.fields().include("key").include("name");
			Map<String, String> byWord = new HashMap<>();
			for (Document project : mongo.query(Project.class).as(Document.class).matching(query).all()) {
				String id = WorkItemDocuments.id(project);
				byWord.put(id.toLowerCase(Locale.ROOT), id);
				if (project.getString("name") != null) {
					byWord.putIfAbsent(project.getString("name").toLowerCase(Locale.ROOT), id);
				}
				if (project.getString("key") != null) {
					byWord.put(project.getString("key").toLowerCase(Locale.ROOT), id);
				}
				projectLabels.put(id, project.getString("key") != null ? project.getString("key")
						: project.getString("name"));
			}
			return byWord;
		}

		private Integer minutes(List<String> record) {
			String minutes = value(record, columns.get(Column.MINUTES));
			if (minutes != null && !minutes.isBlank()) {
				return number(minutes, 1);
			}
			String hours = value(record, columns.get(Column.HOURS));
			if (hours != null && !hours.isBlank()) {
				return clockMinutes(hours) != null ? clockMinutes(hours) : number(hours, 60);
			}
			String duration = value(record, columns.get(Column.DURATION));
			if (duration != null && !duration.isBlank()) {
				return clockMinutes(duration) != null ? clockMinutes(duration) : number(duration, 60);
			}
			return null;
		}
	}

	// Strict, so the 31st of February is refused rather than quietly read as the 28th.
	private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
			DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
			DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT));

	private static final List<DateTimeFormatter> LOCAL_TIMES = List.of(
			DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]"), DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm[:ss]"),
			DateTimeFormatter.ofPattern("d.M.uuuu H:mm[:ss]"));

	static LocalDate date(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String text = value.strip();
		for (DateTimeFormatter format : DATES) {
			try {
				return LocalDate.parse(text, format);
			}
			catch (DateTimeParseException ignored) {
				// The next shape.
			}
		}
		throw ApiException.badRequest("error.time.import.date");
	}

	/**
	 * A moment from a cell: an instant with its offset, a local date and time on the owner's clock,
	 * or a time of day on the row's date — toggl writes date and time in separate columns.
	 */
	static Instant instant(String value, LocalDate date, ZoneId zone) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String text = value.strip();
		try {
			return OffsetDateTime.parse(text).toInstant();
		}
		catch (DateTimeParseException ignored) {
			// A local form, then.
		}
		for (DateTimeFormatter format : LOCAL_TIMES) {
			try {
				return LocalDateTime.parse(text, format).atZone(zone).toInstant();
			}
			catch (DateTimeParseException ignored) {
				// The next shape.
			}
		}
		if (date != null) {
			try {
				return LocalTime.parse(text.length() == 4 ? "0" + text : text).atDate(date).atZone(zone).toInstant();
			}
			catch (DateTimeParseException ignored) {
				// Falls through to the refusal.
			}
		}
		throw ApiException.badRequest("error.time.import.time");
	}

	/** "1:30" or "01:30:00" as minutes, or null when the cell is not a clock reading. */
	static Integer clockMinutes(String value) {
		String[] parts = value.strip().split(":");
		if (parts.length < 2 || parts.length > 3 || Arrays.stream(parts).anyMatch(part -> !part.matches("\\d{1,3}"))) {
			return null;
		}
		int minutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
		return parts.length == 3 && Integer.parseInt(parts[2]) >= 30 ? minutes + 1 : minutes;
	}

	/** A decimal with a point or a comma, times [factor], rounded to a whole minute. */
	static Integer number(String value, int factor) {
		try {
			return new BigDecimal(value.strip().replace(',', '.')).multiply(BigDecimal.valueOf(factor))
					.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
		}
		catch (NumberFormatException | ArithmeticException ex) {
			throw ApiException.badRequest("error.time.import.duration");
		}
	}

	private static List<String> tags(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split("[,;]")).map(String::strip).filter(tag -> !tag.isEmpty()).toList();
	}

	private static Boolean billable(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String word = value.strip().toLowerCase(Locale.ROOT);
		if (TRUE.contains(word)) {
			return true;
		}
		if (FALSE.contains(word)) {
			return false;
		}
		throw ApiException.badRequest("error.time.import.billable", value);
	}

	private static String value(List<String> record, Integer index) {
		return index == null || index >= record.size() ? null : record.get(index);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static String lower(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

	static String normalize(String header) {
		return NOT_A_LETTER.matcher(header == null ? "" : header.toLowerCase(Locale.ROOT)).replaceAll("");
	}

	private static Map<Column, List<String>> synonyms() {
		Map<Column, List<String>> words = new EnumMap<>(Column.class);
		words.put(Column.DATE, List.of("date", "datum", "startdate", "day", "fecha", "дата", "日付", "日期"));
		words.put(Column.START, List.of("startedat", "starttime", "start", "beginn", "begin", "von", "début", "inicio",
				"начало", "開始", "开始"));
		words.put(Column.END, List.of("endedat", "endtime", "end", "ende", "bis", "fin", "конец", "終了", "结束"));
		words.put(Column.MINUTES, List.of("durationminutes", "recordedminutes", "erfassteminuten", "minutessaisies",
				"minutes", "minuten", "minutos", "минуты"));
		words.put(Column.HOURS, List.of("hours", "stunden", "heures", "horas", "часы", "decimalhours"));
		words.put(Column.DURATION, List.of("duration", "dauer", "durée", "duración"));
		words.put(Column.PROJECT, List.of("project", "projekt", "projet", "proyecto", "проект", "projectkey"));
		words.put(Column.ISSUE, List.of("issue", "issuekey", "vorgang", "ticket", "task", "tarea", "задача", "課題"));
		words.put(Column.ACTIVITY, List.of("activitytype", "activity", "tätigkeit", "taetigkeit", "activité",
				"actividad"));
		words.put(Column.DESCRIPTION, List.of("description", "beschreibung", "notes", "notiz", "descripción",
				"описание", "説明", "描述"));
		words.put(Column.TAGS, List.of("tags", "tag", "étiquettes", "etiquetas", "теги", "タグ", "标签"));
		words.put(Column.BILLABLE, List.of("billable", "abrechenbar", "facturable"));
		words.put(Column.USER, List.of("username", "user", "email", "person", "benutzer", "member", "personne",
				"persona"));
		return words;
	}
}
