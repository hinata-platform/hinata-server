package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.block.BlockService;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What moderation holds about a person, for their data export (Art. 15 and 20 DSGVO): whom they
 * block (HIN-168), what they reported, and the cases about their own content (HIN-170).
 *
 * <p>Never who blocks them, and never who reported them or why (Art. 15 Abs. 4 DSGVO, § 29 Abs. 1
 * BDSG): a case about their content shows when, which categories, and how it was decided.
 */
@Component
@RequiredArgsConstructor
public class ModerationPersonalData implements PersonalDataExport {

	private final BlockService blocks;
	private final UserWords words;
	private final MongoTemplate mongo;

	@Override
	public String key() {
		return "moderation";
	}

	@Override
	public Object data(User user) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("blocked", blocks.everyBlockOf(user).stream().map(person -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("userId", person.id());
			row.put("displayName", person.displayName());
			row.put("blockedAt", person.blockedAt());
			return row;
		}).toList());
		List<ContentReport> reports = reportsOf(user);
		Map<String, ModerationCase> theirCases = casesOf(reports);
		out.put("reports", reports.stream().map(report -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("reportedAt", report.getCreatedAt());
			row.put("category", report.getCategory());
			row.put("kind", report.getTargetKind());
			row.put("outcome", outcomeOf(theirCases.get(report.getCaseId())));
			return row;
		}).toList());
		out.put("casesAboutMyContent", casesAbout(user).stream().map(about -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("reportedAt", about.getFirstReportedAt());
			row.put("categories", about.getCategories());
			row.put("outcome", outcomeOf(about));
			row.put("ground", about.getDecision() == null ? null : about.getDecision().ground());
			return row;
		}).toList());
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<Table> tables = new java.util.ArrayList<>();
		List<BlockService.BlockedPerson> blocked = blocks.everyBlockOf(user);
		if (!blocked.isEmpty()) {
			tables.add(new Table(words.in(locale, "export.pdf.blocks.title"),
					List.of(words.in(locale, "export.pdf.blocks.person"), words.in(locale, "export.pdf.blocks.since")),
					new float[]{5, 3},
					blocked.stream().map(person -> List.of(
							person.displayName() == null ? "—" : person.displayName(),
							PersonalDataExport.instant(person.blockedAt()))).toList(),
					null));
		}
		List<ContentReport> reports = reportsOf(user);
		if (!reports.isEmpty()) {
			Map<String, ModerationCase> theirCases = casesOf(reports);
			tables.add(new Table(words.in(locale, "export.pdf.reports.title"),
					List.of(words.in(locale, "export.pdf.reports.date"), words.in(locale, "export.pdf.reports.category"),
							words.in(locale, "export.pdf.reports.kind"), words.in(locale, "export.pdf.reports.outcome")),
					new float[]{3, 3, 3, 3},
					reports.stream().map(report -> List.of(
							PersonalDataExport.instant(report.getCreatedAt()),
							words.in(locale, "moderation.category." + report.getCategory()),
							words.in(locale, "moderation.kind." + report.getTargetKind()),
							words.in(locale, "moderation.outcome." + outcomeOf(theirCases.get(report.getCaseId())))))
							.toList(),
					null));
		}
		List<ModerationCase> about = casesAbout(user);
		if (!about.isEmpty()) {
			tables.add(new Table(words.in(locale, "export.pdf.casesAbout.title"),
					List.of(words.in(locale, "export.pdf.reports.date"), words.in(locale, "export.pdf.reports.category"),
							words.in(locale, "export.pdf.reports.outcome"), words.in(locale, "export.pdf.casesAbout.ground")),
					new float[]{3, 4, 3, 2},
					about.stream().map(c -> List.of(
							PersonalDataExport.instant(c.getFirstReportedAt()),
							c.getCategories().stream().map(category -> words.in(locale, "moderation.category." + category))
									.collect(Collectors.joining(", ")),
							words.in(locale, "moderation.outcome." + outcomeOf(c)),
							c.getDecision() == null || c.getDecision().ground() == null ? "—"
									: words.in(locale, "moderation.ground." + c.getDecision().ground()))).toList(),
					null));
		}
		return tables;
	}

	/** What a case came to, as a key: still open, or its decision's outcome. */
	static String outcomeOf(ModerationCase c) {
		if (c == null || c.getDecision() == null) {
			return "OPEN";
		}
		return c.getDecision().outcome().name();
	}

	private List<ContentReport> reportsOf(User user) {
		Query query = Query.query(Criteria.where("reporterId").is(user.getId()))
				.with(Sort.by(Sort.Direction.DESC, "createdAt"));
		query.fields().include("createdAt", "category", "targetKind", "caseId");
		return mongo.find(query, ContentReport.class);
	}

	private Map<String, ModerationCase> casesOf(List<ContentReport> reports) {
		Set<String> ids = new HashSet<>();
		reports.forEach(report -> ids.add(report.getCaseId()));
		Map<String, ModerationCase> byId = new HashMap<>();
		if (!ids.isEmpty()) {
			Query query = Query.query(Criteria.where("_id").in(ids));
			query.fields().include("decision.outcome");
			mongo.find(query, ModerationCase.class).forEach(c -> byId.put(c.getId(), c));
		}
		return byId;
	}

	/** The cases about content [user] wrote, off {@code contributor_status}; nothing of who reported. */
	private List<ModerationCase> casesAbout(User user) {
		Query query = Query.query(Criteria.where("contributorIds").is(user.getId()))
				.with(Sort.by(Sort.Direction.DESC, "firstReportedAt"));
		query.fields().include("firstReportedAt", "categories", "decision.outcome", "decision.ground");
		return mongo.find(query, ModerationCase.class);
	}
}
