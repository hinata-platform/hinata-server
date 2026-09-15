package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.me.TimePreferences;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The module's part of a person's data export (Art. 15/20 DSGVO): their entries,
 * their running timer, their submissions, the corrections they asked for with the
 * answers they got, the days opened for them, and their timer preferences.
 *
 * <p>Regardless of whether the module is switched on. Entries exist from the 1.x
 * time tracking onwards, and whether an operator has the extended module enabled
 * today changes nothing about what is stored about somebody.
 *
 * <p>Bounded, and says so every time. The JSON carries the {@value #JSON_ENTRY_CAP}
 * newest entries and up to {@value #LIST_CAP} of everything else, each list with a
 * flag when it was cut; the PDF lists at most {@value #TABLE_ROWS} rows per table
 * with a line beneath the table when it did. The export is a document somebody reads;
 * the complete record of entries is the CSV export, and the cap notes name it.
 */
@Component
@RequiredArgsConstructor
public class TimeTrackingPersonalData implements PersonalDataExport {

	static final int JSON_ENTRY_CAP = 5_000;
	static final int LIST_CAP = 1_000;
	static final int TABLE_ROWS = 500;

	/** Longest note a PDF cell carries; the JSON keeps the whole text. */
	static final int PDF_NOTE_CHARS = 300;

	private static final String CSV_ROUTE = "GET /api/v1/time/export.csv";

	private final MongoTemplate mongo;
	private final RunningTimerRepository timers;
	private final UserWords words;

	@Override
	public String key() {
		return "timeTracking";
	}

	@Override
	public Object data(User user) {
		Map<String, Object> out = new LinkedHashMap<>();
		List<WorkItem> entries = entriesOf(user, JSON_ENTRY_CAP + 1);
		putCapped(out, "entries", entries, JSON_ENTRY_CAP, TimeTrackingPersonalData::entry);
		if (entries.size() > JSON_ENTRY_CAP) {
			out.put("entriesNote", "The " + JSON_ENTRY_CAP + " newest entries. " + CSV_ROUTE
					+ " exports up to " + TimeEntryCsvExport.MAX_ROWS + ".");
		}
		out.put("runningTimer", timers.findByUserId(user.getId()).map(TimeTrackingPersonalData::timer)
				.orElse(null));
		putCapped(out, "submissions", submissionsOf(user, LIST_CAP + 1), LIST_CAP,
				TimeTrackingPersonalData::submission);
		putCapped(out, "correctionRequests", requestsOf(user, LIST_CAP + 1), LIST_CAP,
				TimeTrackingPersonalData::request);
		putCapped(out, "backfillGrants", grantsOf(user, LIST_CAP + 1), LIST_CAP,
				TimeTrackingPersonalData::grant);
		out.put("preferences", preferencesOf(user));
		out.put("privacyAcknowledgedAt", user.getTimePrivacyAcknowledgedAt());
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<Table> tables = new ArrayList<>();

		List<WorkItem> entries = entriesOf(user, TABLE_ROWS + 1);
		tables.add(new Table(t(locale, "export.pdf.time.entries"),
				List.of(t(locale, "export.pdf.time.date"), t(locale, "export.pdf.time.minutes"),
						t(locale, "export.pdf.time.activity"), t(locale, "export.pdf.time.description")),
				new float[]{2, 1.4f, 2.4f, 6},
				rows(entries, item -> List.of(String.valueOf(item.getDate()),
						String.valueOf(item.getDurationMinutes()), nz(item.getActivityType()),
						cell(item.getDescription()))),
				entries.size() > TABLE_ROWS ? t(locale, "export.pdf.time.entriesCapped", TABLE_ROWS) : null));

		timers.findByUserId(user.getId()).ifPresent(timer -> tables.add(new Table(
				t(locale, "export.pdf.time.timer"),
				List.of(t(locale, "export.pdf.time.startedAt"), t(locale, "export.pdf.time.mode"),
						t(locale, "export.pdf.time.description")),
				new float[]{3, 2, 6},
				List.of(List.of(PersonalDataExport.instant(timer.getStartedAt()),
						String.valueOf(timer.getMode()), cell(timer.getDescription()))),
				null)));

		List<TimesheetApproval> submissions = submissionsOf(user, TABLE_ROWS + 1);
		tables.add(new Table(t(locale, "export.pdf.time.submissions"),
				List.of(t(locale, "export.pdf.time.period"), t(locale, "export.pdf.time.status"),
						t(locale, "export.pdf.time.decidedAt"), t(locale, "export.pdf.time.note")),
				new float[]{3, 2, 3, 5},
				rows(submissions, approval -> List.of(
						approval.getPeriodStart() + " – " + approval.getPeriodEnd(),
						String.valueOf(approval.getStatus()),
						PersonalDataExport.instant(approval.getDecidedAt()), cell(approval.getNote()))),
				capNote(locale, submissions.size())));

		List<TimeCorrectionRequest> requests = requestsOf(user, TABLE_ROWS + 1);
		tables.add(new Table(t(locale, "export.pdf.time.requests"),
				List.of(t(locale, "export.pdf.time.at"), t(locale, "export.pdf.time.span"),
						t(locale, "export.pdf.time.note"), t(locale, "export.pdf.time.answer")),
				new float[]{3, 3, 5, 5},
				rows(requests, request -> List.of(PersonalDataExport.instant(request.getCreatedAt()),
						spanOf(request), cell(request.getNote()),
						request.getAnswer() == null ? "—" : cell(request.getAnswer().getNote()))),
				capNote(locale, requests.size())));

		List<TimeBackfillGrant> grants = grantsOf(user, TABLE_ROWS + 1);
		if (!grants.isEmpty()) {
			tables.add(new Table(t(locale, "export.pdf.time.grants"),
					List.of(t(locale, "export.pdf.time.span"), t(locale, "export.pdf.time.expiresAt"),
							t(locale, "export.pdf.time.note")),
					new float[]{3, 3, 6},
					rows(grants, grant -> List.of(grant.getFrom() + " – " + grant.getTo(),
							PersonalDataExport.instant(grant.getExpiresAt()), cell(grant.getNote()))),
					capNote(locale, grants.size())));
		}

		TimePreferences prefs = preferencesOf(user);
		tables.add(new Table(t(locale, "export.pdf.time.preferences"),
				List.of(t(locale, "export.pdf.time.setting"), t(locale, "export.pdf.time.value")),
				new float[]{4, 6},
				List.of(
						List.of(t(locale, "export.pdf.time.pref.pomodoroWork"),
								String.valueOf(prefs.getPomodoroWork())),
						List.of(t(locale, "export.pdf.time.pref.pomodoroShortBreak"),
								String.valueOf(prefs.getPomodoroShortBreak())),
						List.of(t(locale, "export.pdf.time.pref.pomodoroLongBreak"),
								String.valueOf(prefs.getPomodoroLongBreak())),
						List.of(t(locale, "export.pdf.time.pref.pomodoroCycles"),
								String.valueOf(prefs.getPomodoroCycles())),
						List.of(t(locale, "export.pdf.time.pref.countdownMinutes"),
								String.valueOf(prefs.getCountdownMinutes())),
						List.of(t(locale, "export.pdf.time.pref.sound"),
								t(locale, prefs.isSound() ? "export.pdf.yes" : "export.pdf.no")),
						List.of(t(locale, "export.pdf.time.pref.dailyTarget"),
								prefs.getDailyTargetMinutes() == null ? "–"
										: String.valueOf(prefs.getDailyTargetMinutes())),
						List.of(t(locale, "export.pdf.time.pref.dailyReminderAt"),
								clock(prefs.getDailyReminderAt())),
						List.of(t(locale, "export.pdf.time.pref.weeklyTarget"),
								prefs.getWeeklyTargetMinutes() == null ? "–"
										: String.valueOf(prefs.getWeeklyTargetMinutes())),
						List.of(t(locale, "export.pdf.time.pref.weeklyReminderAt"),
								prefs.getWeeklyReminderDay() + " " + clock(prefs.getWeeklyReminderAt())),
						List.of(t(locale, "export.pdf.time.acknowledged"),
								PersonalDataExport.instant(user.getTimePrivacyAcknowledgedAt()))),
				null));
		return tables;
	}

	// --- reads -------------------------------------------------------------------

	private List<WorkItem> entriesOf(User user, int limit) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Order.desc("date"), Sort.Order.desc("startedAt"),
						Sort.Order.desc("_id")))
				.limit(limit), WorkItem.class);
	}

	/** Without the event history: eight fields are exported, and the history can hold fifty events. */
	private List<TimesheetApproval> submissionsOf(User user, int limit) {
		Query query = Query.query(Criteria.where("userId").is(user.getId()))
				.with(TimesheetApprovalRepository.NEWEST_FIRST).limit(limit);
		query.fields().exclude("history");
		return mongo.find(query, TimesheetApproval.class);
	}

	private List<TimeCorrectionRequest> requestsOf(User user, int limit) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Order.desc("createdAt"))).limit(limit), TimeCorrectionRequest.class);
	}

	private List<TimeBackfillGrant> grantsOf(User user, int limit) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Order.desc("grantedAt"))).limit(limit), TimeBackfillGrant.class);
	}

	private static TimePreferences preferencesOf(User user) {
		TimePreferences stored = user.getTimePreferences();
		return stored == null ? TimePreferences.defaults() : stored.sanitized();
	}

	/** A minute of the day as a clock reads it, "17:00". */
	private static String clock(int minuteOfDay) {
		return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
	}

	// --- shapes ------------------------------------------------------------------

	private static <T> void putCapped(Map<String, Object> out, String key, List<T> found, int cap,
			java.util.function.Function<T, Object> shape) {
		out.put(key, found.stream().limit(cap).map(shape).toList());
		out.put(key + "Truncated", found.size() > cap);
	}

	private static <T> List<List<String>> rows(List<T> found,
			java.util.function.Function<T, List<String>> row) {
		return found.stream().limit(TABLE_ROWS).map(row).toList();
	}

	private String capNote(Locale locale, int found) {
		return found > TABLE_ROWS ? t(locale, "export.pdf.time.listCapped", TABLE_ROWS) : null;
	}

	private static String spanOf(TimeCorrectionRequest request) {
		if (request.getKind() == TimeCorrectionRequest.Kind.SPAN) {
			return request.getFrom() + " – " + request.getTo();
		}
		return String.valueOf(request.getDate());
	}

	private static Map<String, Object> entry(WorkItem item) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", item.getId());
		out.put("date", item.getDate());
		out.put("durationMinutes", item.getDurationMinutes());
		out.put("startedAt", item.getStartedAt());
		out.put("endedAt", item.getEndedAt());
		out.put("projectId", item.getProjectId());
		out.put("issueId", item.getIssueId());
		out.put("activityType", item.getActivityType());
		out.put("description", item.getDescription());
		out.put("tags", item.getTags());
		out.put("billable", item.isBillable());
		out.put("source", item.getSource().name());
		out.put("createdAt", item.getCreatedAt());
		out.put("updatedAt", item.getUpdatedAt());
		out.put("updatedBy", item.getUpdatedBy());
		return out;
	}

	private static Map<String, Object> timer(RunningTimer timer) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("startedAt", timer.getStartedAt());
		out.put("mode", timer.getMode());
		out.put("projectId", timer.getProjectId());
		out.put("issueId", timer.getIssueId());
		out.put("description", timer.getDescription());
		out.put("activityType", timer.getActivityType());
		out.put("tags", timer.getTags());
		out.put("billable", timer.isBillable());
		return out;
	}

	private static Map<String, Object> submission(TimesheetApproval approval) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", approval.getId());
		out.put("projectId", approval.getProjectId());
		out.put("periodStart", approval.getPeriodStart());
		out.put("periodEnd", approval.getPeriodEnd());
		out.put("status", approval.getStatus());
		out.put("submittedAt", approval.getSubmittedAt());
		out.put("decidedAt", approval.getDecidedAt());
		out.put("note", approval.getNote());
		return out;
	}

	private static Map<String, Object> request(TimeCorrectionRequest request) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", request.getId());
		out.put("kind", request.getKind());
		out.put("entryId", request.getWorkItemId());
		out.put("date", request.getDate());
		out.put("from", request.getFrom());
		out.put("to", request.getTo());
		out.put("reason", request.getReason());
		out.put("note", request.getNote());
		out.put("createdAt", request.getCreatedAt());
		TimeCorrectionRequest.Answer answer = request.getAnswer();
		if (answer != null) {
			Map<String, Object> reply = new LinkedHashMap<>();
			reply.put("note", answer.getNote());
			reply.put("at", answer.getAt());
			reply.put("granted", answer.isGranted());
			out.put("answer", reply);
		}
		return out;
	}

	private static Map<String, Object> grant(TimeBackfillGrant grant) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", grant.getFrom());
		out.put("to", grant.getTo());
		out.put("note", grant.getNote());
		out.put("grantedAt", grant.getGrantedAt());
		out.put("expiresAt", grant.getExpiresAt());
		return out;
	}

	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}

	/** A note as a PDF cell: whole up to {@value #PDF_NOTE_CHARS} characters, cut with an ellipsis after. */
	private static String cell(String value) {
		if (value == null || value.isBlank()) {
			return "—";
		}
		return value.length() <= PDF_NOTE_CHARS ? value : value.substring(0, PDF_NOTE_CHARS) + "…";
	}

	private static String nz(Object value) {
		return value == null ? "" : value.toString();
	}
}
