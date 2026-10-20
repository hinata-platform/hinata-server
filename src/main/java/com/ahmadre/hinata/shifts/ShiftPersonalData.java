package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shift planning's part of a person's data export (Art. 15 and 20 DSGVO): every assignment held
 * about them (which shift of which event, when, in what status and who put them there), the
 * projects they plan shifts in (HIN-45) and whether they subscribe to their shifts as a calendar
 * feed (HIN-47, never the token itself).
 *
 * <p>Exported whether or not the module is switched on today: Art. 15 is about the data held, not
 * about the current configuration. The planner and feed collections are read by name, so the
 * export already answers for them on the day those stages store anything.
 */
@Component
@RequiredArgsConstructor
public class ShiftPersonalData implements PersonalDataExport {

	static final int CAP = 2_000;
	static final int TABLE_ROWS = 500;
	static final String PLANNERS = "shift_planners";
	static final String FEED_TOKENS = "shift_feed_tokens";

	private final MongoTemplate mongo;
	private final UserWords words;

	@Override
	public String key() {
		return "shifts";
	}

	@Override
	public Object data(User user) {
		List<ShiftAssignment> held = assignments(user, CAP + 1);
		Context context = context(held.stream().limit(CAP).toList());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("assignments", held.stream().limit(CAP).map(assignment -> row(assignment, context)).toList());
		out.put("assignmentsTruncated", held.size() > CAP);
		out.put("plannerRoles", plannerRoles(user));
		out.put("feedSubscription", feed(user));
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<ShiftAssignment> held = assignments(user, TABLE_ROWS + 1);
		List<ShiftAssignment> shown = held.stream().limit(TABLE_ROWS).toList();
		Context context = context(shown);
		Table assignments = new Table(t(locale, "export.pdf.shifts.assignments"),
				List.of(t(locale, "export.pdf.shifts.event"), t(locale, "export.pdf.shifts.shift"),
						t(locale, "export.pdf.shifts.starts"), t(locale, "export.pdf.shifts.ends"),
						t(locale, "export.pdf.shifts.status"), t(locale, "export.pdf.shifts.assignedBy")),
				new float[]{3, 2.5f, 2.2f, 2.2f, 1.5f, 2},
				shown.stream().map(assignment -> List.of(
						text(context.eventTitles().get(assignment.getEventId())),
						text(context.shiftTitles().get(assignment.getShiftId())),
						PersonalDataExport.instant(assignment.getStartsAt()),
						PersonalDataExport.instant(assignment.getEndsAt()),
						String.valueOf(assignment.getStatus()),
						assignment.getAssignedBy() == null ? t(locale, "export.pdf.shifts.self")
								: text(context.names().get(assignment.getAssignedBy()))))
						.toList(),
				held.size() > TABLE_ROWS ? t(locale, "export.pdf.shifts.truncated", TABLE_ROWS) : null);
		List<Map<String, Object>> roles = plannerRoles(user);
		Table planners = new Table(t(locale, "export.pdf.shifts.plannerRoles"),
				List.of(t(locale, "export.pdf.shifts.project"), t(locale, "export.pdf.shifts.since")),
				new float[]{3, 2},
				roles.stream().map(role -> List.of(text((String) role.get("projectId")),
						PersonalDataExport.instant(role.get("since") instanceof Date date ? date.toInstant() : null)))
						.toList(),
				null);
		return List.of(assignments, planners);
	}

	private List<ShiftAssignment> assignments(User user, int limit) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Direction.DESC, "startsAt")).limit(limit), ShiftAssignment.class);
	}

	private Map<String, Object> row(ShiftAssignment assignment, Context context) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("shiftId", assignment.getShiftId());
		row.put("shiftTitle", context.shiftTitles().get(assignment.getShiftId()));
		row.put("eventId", assignment.getEventId());
		row.put("eventTitle", context.eventTitles().get(assignment.getEventId()));
		row.put("projectId", assignment.getProjectId());
		row.put("startsAt", assignment.getStartsAt());
		row.put("endsAt", assignment.getEndsAt());
		row.put("status", assignment.getStatus());
		row.put("cancelled", assignment.isCancelled());
		row.put("assignedBy", assignment.getAssignedBy());
		row.put("assignedAt", assignment.getCreatedAt());
		return row;
	}

	private List<Map<String, Object>> plannerRoles(User user) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Document role : mongo.getCollection(PLANNERS).find(new Document("userId", user.getId()))) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("projectId", role.getString("projectId"));
			row.put("grantedBy", role.getString("grantedBy"));
			row.put("since", role.get("createdAt"));
			out.add(row);
		}
		return out;
	}

	/** Whether a feed exists and when it was made and last read; the token is never part of it. */
	private Map<String, Object> feed(User user) {
		Document token = mongo.getCollection(FEED_TOKENS).find(new Document("userId", user.getId())).first();
		if (token == null) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("createdAt", token.get("createdAt"));
		out.put("lastUsedAt", token.get("lastUsedAt"));
		return out;
	}

	/** The titles and names the rows point at, one query each. */
	private Context context(List<ShiftAssignment> rows) {
		Map<String, String> shifts = titles(rows.stream().map(ShiftAssignment::getShiftId).toList(), Shift.class,
				Shift::getId, Shift::getTitle);
		Map<String, String> events = titles(rows.stream().map(ShiftAssignment::getEventId).toList(), ShiftEvent.class,
				ShiftEvent::getId, ShiftEvent::getTitle);
		Map<String, String> names = titles(rows.stream().map(ShiftAssignment::getAssignedBy)
						.filter(id -> id != null).toList(), User.class, User::getId,
				person -> person.getDisplayName() != null ? person.getDisplayName() : person.getUsername());
		return new Context(shifts, events, names);
	}

	private <T> Map<String, String> titles(Collection<String> ids, Class<T> type, Function<T, String> id,
			Function<T, String> title) {
		List<String> distinct = ids.stream().filter(value -> value != null).distinct().toList();
		if (distinct.isEmpty()) {
			return Map.of();
		}
		return mongo.find(Query.query(Criteria.where("_id").in(distinct)), type).stream()
				.filter(row -> title.apply(row) != null)
				.collect(Collectors.toMap(id, title, (a, b) -> a));
	}

	private record Context(Map<String, String> shiftTitles, Map<String, String> eventTitles,
			Map<String, String> names) {
	}

	private static String text(String value) {
		return value == null || value.isBlank() ? "—" : value;
	}

	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}
}
