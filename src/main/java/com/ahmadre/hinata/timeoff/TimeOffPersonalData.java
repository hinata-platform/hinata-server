package com.ahmadre.hinata.timeoff;

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

/**
 * This module's part of a person's data export (Art. 15/20 DSGVO): what they were granted, every
 * movement of their balances, and the two employment dates the arithmetic uses.
 *
 * <p>Exported whether or not the module is switched on today. A person asking what is held about
 * them is owed the answer that is actually held, not the answer the current configuration would
 * produce — Art. 15 is about the data, not about the feature.
 *
 * <p>The type is exported by key rather than by display name: the key is what the rows point at,
 * and a name an operator changed later would make an old row read as something it never was.
 */
@Component
@RequiredArgsConstructor
public class TimeOffPersonalData implements PersonalDataExport {

	static final int LEDGER_CAP = 2_000;
	static final int TABLE_ROWS = 500;

	private final MongoTemplate mongo;
	private final TimeOffTypeRepository types;
	private final UserWords words;

	@Override
	public String key() {
		return "timeOff";
	}

	@Override
	public Object data(User user) {
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, String> keys = typeKeys();
		out.put("employment", employmentOf(user).map(TimeOffPersonalData::employment).orElse(null));
		out.put("entitlements", entitlementsOf(user).stream()
				.map(entitlement -> entitlement(entitlement, keys)).toList());
		List<TimeOffLedgerEntry> movements = ledgerOf(user, LEDGER_CAP + 1);
		out.put("balanceMovements", movements.stream().limit(LEDGER_CAP)
				.map(entry -> movement(entry, keys)).toList());
		out.put("balanceMovementsTruncated", movements.size() > LEDGER_CAP);
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		Map<String, String> keys = typeKeys();
		Table entitlements = new Table(t(locale, "export.pdf.timeOff.entitlements"),
				List.of(t(locale, "export.pdf.timeOff.type"), t(locale, "export.pdf.timeOff.year"),
						t(locale, "export.pdf.timeOff.allowance"), t(locale, "export.pdf.timeOff.accrued")),
				new float[]{3, 1.5f, 2, 2},
				entitlementsOf(user).stream()
						.map(entitlement -> List.of(
								keys.getOrDefault(entitlement.getTypeId(), "—"),
								String.valueOf(entitlement.getYear()),
								days(locale, entitlement.allowanceMilliDays()),
								days(locale, entitlement.accruedMilliDays())))
						.toList(),
				null);
		List<TimeOffLedgerEntry> movements = ledgerOf(user, TABLE_ROWS + 1);
		Table ledger = new Table(t(locale, "export.pdf.timeOff.ledger"),
				List.of(t(locale, "export.pdf.timeOff.on"), t(locale, "export.pdf.timeOff.type"),
						t(locale, "export.pdf.timeOff.kind"), t(locale, "export.pdf.timeOff.days"),
						t(locale, "export.pdf.timeOff.reason")),
				new float[]{2, 2.5f, 2, 1.5f, 4},
				movements.stream().limit(TABLE_ROWS)
						.map(entry -> List.of(String.valueOf(entry.getEffectiveOn()),
								keys.getOrDefault(entry.getTypeId(), "—"),
								String.valueOf(entry.getKind()),
								days(locale, entry.milliDays()),
								entry.getReason() == null ? "—" : entry.getReason()))
						.toList(),
				movements.size() > TABLE_ROWS ? t(locale, "export.pdf.time.listCapped", TABLE_ROWS) : null);
		return List.of(entitlements, ledger);
	}

	// --- reading -----------------------------------------------------------------

	private java.util.Optional<TimeOffEmployment> employmentOf(User user) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId())).limit(1),
				TimeOffEmployment.class).stream().findFirst();
	}

	private List<TimeOffEntitlement> entitlementsOf(User user) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Order.desc("year"))).limit(TABLE_ROWS), TimeOffEntitlement.class);
	}

	private List<TimeOffLedgerEntry> ledgerOf(User user, int limit) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Order.desc("effectiveOn"), Sort.Order.desc("_id"))).limit(limit),
				TimeOffLedgerEntry.class);
	}

	/** Type id to key, read once: an export of a hundred rows should not be a hundred lookups. */
	private Map<String, String> typeKeys() {
		Map<String, String> keys = new LinkedHashMap<>();
		types.findAll().forEach(type -> keys.put(type.getId(), type.getKey()));
		return keys;
	}

	// --- shapes -------------------------------------------------------------------

	private static Map<String, Object> employment(TimeOffEmployment facts) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("hiredOn", facts.getHiredOn());
		out.put("leftOn", facts.getLeftOn());
		out.put("note", facts.getNote());
		return out;
	}

	private static Map<String, Object> entitlement(TimeOffEntitlement entitlement,
			Map<String, String> keys) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("type", keys.get(entitlement.getTypeId()));
		out.put("year", entitlement.getYear());
		out.put("allowanceMilliDays", entitlement.allowanceMilliDays());
		out.put("accruedMilliDays", entitlement.accruedMilliDays());
		out.put("source", entitlement.getSource());
		out.put("note", entitlement.getNote());
		out.put("grantedAt", entitlement.getGrantedAt());
		return out;
	}

	private static Map<String, Object> movement(TimeOffLedgerEntry entry, Map<String, String> keys) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("type", keys.get(entry.getTypeId()));
		out.put("year", entry.getYear());
		out.put("kind", entry.getKind());
		out.put("milliDays", entry.milliDays());
		out.put("effectiveOn", entry.getEffectiveOn());
		out.put("reason", entry.getReason());
		out.put("createdAt", entry.getCreatedAt());
		return out;
	}

	private String days(Locale locale, int milliDays) {
		return words.in(locale, "notify.timeOff.days", milliDays / 1000.0);
	}

	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}
}
