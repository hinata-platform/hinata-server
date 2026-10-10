package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The invitations a person sent and received (HIN-95), in their own data export: with the state,
 * the other person and the entry as it was offered. Up to {@value #LIST_CAP} per side in the JSON
 * and {@value #TABLE_ROWS} in the PDF, each with a flag or a line when cut.
 */
@Component
@RequiredArgsConstructor
public class TimeSharePersonalData implements PersonalDataExport {

	static final int LIST_CAP = 1_000;
	static final int TABLE_ROWS = 500;

	private final MongoTemplate mongo;
	private final UserRepository users;
	private final UserWords words;

	@Override
	public String key() {
		return "sharedTimeEntries";
	}

	@Override
	public Object data(User user) {
		Map<String, Object> out = new LinkedHashMap<>();
		putCapped(out, "sent", sharesOf("fromUserId", user, LIST_CAP + 1));
		putCapped(out, "received", sharesOf("toUserId", user, LIST_CAP + 1));
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<TimeEntryShare> sent = sharesOf("fromUserId", user, TABLE_ROWS + 1);
		List<TimeEntryShare> received = sharesOf("toUserId", user, TABLE_ROWS + 1);
		if (sent.isEmpty() && received.isEmpty()) {
			return List.of();
		}
		Set<String> others = new LinkedHashSet<>();
		sent.forEach(share -> others.add(share.getToUserId()));
		received.forEach(share -> others.add(share.getFromUserId()));
		Map<String, String> names = new HashMap<>();
		users.findAllById(others).forEach(other -> names.put(other.getId(), other.getDisplayName()));
		List<Table> tables = new ArrayList<>();
		tables.add(table(locale, "export.pdf.timeShares.sent", "export.pdf.timeShares.to", sent,
				share -> names.getOrDefault(share.getToUserId(), "—")));
		tables.add(table(locale, "export.pdf.timeShares.received", "export.pdf.timeShares.from", received,
				share -> names.getOrDefault(share.getFromUserId(), "—")));
		return tables;
	}

	private Table table(Locale locale, String titleKey, String personKey, List<TimeEntryShare> rows,
			java.util.function.Function<TimeEntryShare, String> person) {
		return new Table(t(locale, titleKey),
				List.of(t(locale, "export.pdf.timeShares.at"), t(locale, personKey),
						t(locale, "export.pdf.timeShares.date"), t(locale, "export.pdf.timeShares.status"),
						t(locale, "export.pdf.time.description")),
				new float[]{3, 3, 2, 2, 5},
				rows.stream().limit(TABLE_ROWS).map(share -> List.of(
						PersonalDataExport.instant(share.getCreatedAt()), person.apply(share),
						share.getEntry() == null ? "—" : String.valueOf(share.getEntry().getDate()),
						share.getStatus().name(),
						share.getEntry() == null || share.getEntry().getDescription() == null ? "—"
								: share.getEntry().getDescription())).toList(),
				rows.size() > TABLE_ROWS ? t(locale, "export.pdf.time.listCapped", TABLE_ROWS) : null);
	}

	private List<TimeEntryShare> sharesOf(String side, User user, int limit) {
		return mongo.find(Query.query(Criteria.where(side).is(user.getId()))
				.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id"))).limit(limit),
				TimeEntryShare.class);
	}

	private static void putCapped(Map<String, Object> out, String key, List<TimeEntryShare> found) {
		out.put(key, found.stream().limit(LIST_CAP).map(TimeSharePersonalData::share).toList());
		out.put(key + "Truncated", found.size() > LIST_CAP);
	}

	private static Map<String, Object> share(TimeEntryShare share) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", share.getId());
		out.put("fromUserId", share.getFromUserId());
		out.put("toUserId", share.getToUserId());
		out.put("projectId", share.getProjectId());
		out.put("status", share.getStatus().name());
		out.put("createdAt", share.getCreatedAt());
		out.put("decidedAt", share.getDecidedAt());
		out.put("entry", share.getEntry());
		return out;
	}

	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}
}
