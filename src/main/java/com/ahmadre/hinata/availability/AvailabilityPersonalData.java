package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This package's part of a person's data export (Art. 15/20 DSGVO): their working-time patterns
 * and their absences, notes included, whether or not the module is switched on today.
 */
@Component
@RequiredArgsConstructor
public class AvailabilityPersonalData implements PersonalDataExport {

	static final int ABSENCES_CAP = 1_000;
	static final int TABLE_ROWS = 500;

	private final MongoTemplate mongo;
	private final UserWords words;

	@Override
	public String key() {
		return "availability";
	}

	@Override
	public Object data(User user) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("workingTimePatterns", patternsOf(user).stream().map(AvailabilityPersonalData::pattern).toList());
		List<TimeOff> absences = absencesOf(user, ABSENCES_CAP + 1);
		out.put("absences", absences.stream().limit(ABSENCES_CAP).map(AvailabilityPersonalData::absence).toList());
		out.put("absencesTruncated", absences.size() > ABSENCES_CAP);
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		Table patterns = new Table(t(locale, "export.pdf.availability.patterns"),
				List.of(t(locale, "export.pdf.availability.validFrom"), t(locale, "export.pdf.availability.minutes")),
				new float[]{2, 6},
				patternsOf(user).stream()
						.map(pattern -> List.of(String.valueOf(pattern.getValidFrom()), minutes(pattern)))
						.toList(),
				null);
		List<TimeOff> absences = absencesOf(user, TABLE_ROWS + 1);
		Table absenceTable = new Table(t(locale, "export.pdf.availability.absences"),
				List.of(t(locale, "export.pdf.availability.type"), t(locale, "export.pdf.availability.from"),
						t(locale, "export.pdf.availability.to"), t(locale, "export.pdf.availability.halfDay"),
						t(locale, "export.pdf.availability.note")),
				new float[]{2, 2, 2, 1.4f, 5},
				absences.stream().limit(TABLE_ROWS)
						.map(item -> List.of(t(locale, "export.pdf.availability.type." + item.getType()),
								String.valueOf(item.getFrom()), String.valueOf(item.getTo()),
								t(locale, item.isHalfDay() ? "export.pdf.yes" : "export.pdf.no"),
								item.getNote() == null ? "—" : item.getNote()))
						.toList(),
				absences.size() > TABLE_ROWS ? t(locale, "export.pdf.time.listCapped", TABLE_ROWS) : null);
		return List.of(patterns, absenceTable);
	}

	private List<WorkingSchedule> patternsOf(User user) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Order.desc("validFrom"))).limit(WorkingSchedule.HISTORY_MAX), WorkingSchedule.class);
	}

	private List<TimeOff> absencesOf(User user, int limit) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Order.desc("from"), Sort.Order.desc("_id"))).limit(limit), TimeOff.class);
	}

	private static Map<String, Object> pattern(WorkingSchedule pattern) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("validFrom", pattern.getValidFrom());
		out.put("minutesPerWeekday", pattern.getMinutesPerWeekday());
		out.put("holidayCalendarId", pattern.getHolidayCalendarId());
		out.put("createdAt", pattern.getCreatedAt());
		return out;
	}

	private static Map<String, Object> absence(TimeOff item) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", item.getId());
		out.put("type", item.getType());
		out.put("from", item.getFrom());
		out.put("to", item.getTo());
		out.put("halfDay", item.isHalfDay());
		out.put("note", item.getNote());
		out.put("createdAt", item.getCreatedAt());
		return out;
	}

	private static String minutes(WorkingSchedule pattern) {
		List<Integer> minutes = pattern.getMinutesPerWeekday();
		return minutes == null ? "—" : minutes.stream().map(String::valueOf).collect(Collectors.joining(" · "));
	}

	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}
}
