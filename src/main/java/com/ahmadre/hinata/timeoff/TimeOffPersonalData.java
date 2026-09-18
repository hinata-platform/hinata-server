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
 * movement of their balances, the requests they made and what became of them, and the two
 * employment dates the arithmetic uses.
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
		List<TimeOffRequest> filed = requestsOf(user, LEDGER_CAP + 1);
		out.put("requests", filed.stream().limit(LEDGER_CAP)
				.map(request -> request(request, keys)).toList());
		out.put("requestsTruncated", filed.size() > LEDGER_CAP);
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
								words.timeOffDays(locale, entitlement.allowanceMilliDays()),
								words.timeOffDays(locale, entitlement.accruedMilliDays())))
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
								words.timeOffDays(locale, entry.milliDays()),
								entry.getReason() == null ? "—" : entry.getReason()))
						.toList(),
				movements.size() > TABLE_ROWS ? t(locale, "export.pdf.time.listCapped", TABLE_ROWS) : null);
		List<TimeOffRequest> filed = requestsOf(user, TABLE_ROWS + 1);
		Table asked = new Table(t(locale, "export.pdf.timeOff.requests"),
				List.of(t(locale, "export.pdf.timeOff.on"), t(locale, "export.pdf.timeOff.type"),
						t(locale, "export.pdf.timeOff.days"), t(locale, "export.pdf.timeOff.status"),
						t(locale, "export.pdf.timeOff.decision")),
				new float[]{2, 2.5f, 1.5f, 2, 4},
				filed.stream().limit(TABLE_ROWS)
						.map(request -> List.of(request.getFrom() + " – " + request.getTo(),
								keys.getOrDefault(request.getTypeId(), "—"),
								words.timeOffDays(locale, request.milliDays()),
								String.valueOf(request.getStatus()),
								// The reason a refusal gave, in the answer it belongs in. It is kept
								// out of the audit log so that it lives here and nowhere else.
								request.getDecisionNote() == null ? "—" : request.getDecisionNote()))
						.toList(),
				filed.size() > TABLE_ROWS ? t(locale, "export.pdf.time.listCapped", TABLE_ROWS) : null);
		return List.of(entitlements, ledger, asked);
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

	private List<TimeOffRequest> requestsOf(User user, int limit) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by(Sort.Order.desc("from"), Sort.Order.desc("_id"))).limit(limit),
				TimeOffRequest.class);
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

	/**
	 * One request, with every sentence anybody wrote on it.
	 *
	 * <p>The note and the decision's reason are in here, and only here. Art. 15 asks for the data
	 * held about the person, and a refusal they were given is the clearest example of it — it is
	 * kept out of the audit log for exactly the reason it belongs in this answer: it is theirs.
	 */
	private static Map<String, Object> request(TimeOffRequest request, Map<String, String> keys) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("type", keys.get(request.getTypeId()));
		out.put("from", request.getFrom());
		out.put("to", request.getTo());
		out.put("milliDays", request.milliDays());
		out.put("status", request.getStatus());
		out.put("note", request.getNote());
		out.put("decidedBy", request.getDecidedBy());
		out.put("decidedAt", request.getDecidedAt());
		out.put("decisionNote", request.getDecisionNote());
		out.put("substituteId", request.getSubstituteId());
		out.put("createdAt", request.getCreatedAt());
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

	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}
}
