package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The yearly figures keep the Commission's template to the letter (HIN-178): the head and the row
 * keys of every part as Annex I of Implementing Regulation (EU) 2024/2835 has them, in the copies
 * of the Commission's CSV parts under {@code src/test/resources/moderation/transparency-template}
 * (source: https://digital-strategy.ec.europa.eu/en/library/implementing-regulation-laying-down-templates-concerning-transparency-reporting-obligations,
 * see {@code SOURCE.md} there). Every value is a number, a date, the period or a name the template
 * defines, and nothing an administrator typed can start a formula.
 */
class AnnualFiguresTemplateTest {

	/** The columns Hinata fills; everything else stands as the template has it. */
	private static final Set<String> FILLED = Set.of("Service", "Reporting period", "Value",
			"Description of the sub-category \"Other\"", "Contextual information");

	private static final Pattern NUMBER_OR_DATE = Pattern.compile(
			"|\\d+|\\d{4}-\\d{2}-\\d{2}|\\d{4}-\\d{2}-\\d{2}/\\d{4}-\\d{2}-\\d{2}");

	private final MongoTemplate mongo = mock(MongoTemplate.class);
	private final SettingsService settings = mock(SettingsService.class);
	private AnnualFigures figures;

	@BeforeEach
	void figuresOfAYear() {
		ServerSettings stored = new ServerSettings();
		stored.setOrganizationName("=HYPERLINK(\"http://evil\")");
		ServerSettings.Moderation moderation = new ServerSettings.Moderation();
		moderation.setOperatorName("@Werkhaus e. V.");
		stored.setModeration(moderation);
		when(settings.get()).thenReturn(stored);
		when(mongo.find(any(Query.class), eq(ModerationStat.class))).thenReturn(List.of(
				stat(StatMetric.NOTICE, "HARASSMENT", 3),
				stat(StatMetric.NOTICE, "SPAM", 1),
				stat(StatMetric.NOTICE_ACTION, "HARASSMENT:RULES", 2),
				stat(StatMetric.NOTICE_HOURS, "HARASSMENT:5", 1),
				stat(StatMetric.NOTICE_HOURS, "HARASSMENT:30", 1),
				stat(StatMetric.OWN_MEASURE, "OTHER:RULES:OWN_INITIATIVE", 1),
				stat(StatMetric.OWN_RESTRICTION, "OTHER:RULES:SUSPENSION", 1),
				stat(StatMetric.OWN_MEASURE, "THREAT:RULES:AUTOMATED_DETECTION", 1),
				stat(StatMetric.OWN_RESTRICTION, "THREAT:RULES:REMOVAL", 1),
				stat(StatMetric.APPEAL_FILED, "REMOVAL", 1),
				stat(StatMetric.APPEAL_RESULT, "REMOVAL:REVERSE", 1),
				stat(StatMetric.APPEAL_HOURS, "REMOVAL:12", 1),
				stat(StatMetric.TEXT_REFUSED, "THREAT", 4),
				stat(StatMetric.UPLOAD_REFUSED, "ATTACHMENT", 2)));
		figures = new AnnualFigures(mongo, settings,
				Clock.fixed(Instant.parse("2027-01-15T10:00:00Z"), ZoneOffset.UTC));
	}

	private static ModerationStat stat(StatMetric metric, String key, long count) {
		return ModerationStat.builder().year(2026).metric(metric).key(key).count(count).build();
	}

	@Test
	void everyPartHasTheHeadAndTheRowsOfTheCommissionsTemplate() throws IOException {
		Map<String, List<List<String>>> exported = unzip(figures.zip(2026));
		assertThat(exported.keySet()).containsExactlyElementsOf(AnnualFigures.PARTS.stream()
				.map(part -> part + ".csv").toList());

		for (String part : AnnualFigures.PARTS) {
			List<List<String>> template = official(part);
			List<List<String>> rows = exported.get(part + ".csv");
			assertThat(rows.getFirst()).as(part).isEqualTo(template.getFirst());
			List<List<String>> kept = withoutAddedOthers(rows, template.getFirst());
			assertThat(kept).as(part + ": no row missing, none added").hasSameSizeAs(template);
			for (int r = 1; r < template.size(); r++) {
				for (int c = 0; c < template.get(r).size(); c++) {
					String head = template.getFirst().get(c).strip();
					if (!FILLED.contains(head) && !template.get(r).get(c).isBlank()) {
						assertThat(kept.get(r).get(c)).as(part + " row " + r + " " + head)
								.isEqualTo(template.get(r).get(c));
					}
				}
			}
		}
	}

	@Test
	void everyValueIsANumberADateOrATemplateName() throws IOException {
		Map<String, List<List<String>>> exported = unzip(figures.zip(2026));
		for (Map.Entry<String, List<List<String>>> part : exported.entrySet()) {
			List<String> head = part.getValue().getFirst();
			for (List<String> row : part.getValue().subList(1, part.getValue().size())) {
				for (int c = 0; c < row.size(); c++) {
					String name = head.get(c).strip();
					String value = row.get(c);
					assertThat(value).as(part.getKey() + " " + name)
							.doesNotStartWith("=").doesNotStartWith("+").doesNotStartWith("@");
					if (name.startsWith("Number") || name.startsWith("Median") || name.startsWith("Visibility")
							|| name.startsWith("Account") || name.equals("Reporting period")) {
						assertThat(value).as(part.getKey() + " " + name).matches(NUMBER_OR_DATE);
					}
				}
			}
		}
	}

	@Test
	void theFiguresStandWhereTheTemplateAsksForThem() {
		Map<String, List<List<String>>> parts = figures.parts(2026);

		List<List<String>> notices = parts.get("4_notices");
		assertThat(cell(notices, "TOTAL", AnnualFigures.NOTICES)).isEqualTo("4");
		assertThat(cell(notices, "STATEMENT_CATEGORY_CYBER_VIOLENCE", AnnualFigures.NOTICES)).isEqualTo("3");
		assertThat(cell(notices, "KEYWORD_CYBER_HARASSMENT", AnnualFigures.NOTICES)).isEqualTo("3");
		assertThat(cell(notices, "STATEMENT_CATEGORY_CYBER_VIOLENCE", AnnualFigures.RULES_ACTIONS)).isEqualTo("2");
		assertThat(cell(notices, "STATEMENT_CATEGORY_CYBER_VIOLENCE", AnnualFigures.LAW_ACTIONS)).isEqualTo("0");
		// The lower of the two in the middle, in hours.
		assertThat(cell(notices, "TOTAL", AnnualFigures.MEDIAN_ACTION)).isEqualTo("5");
		// Nobody acted on anything about animals, but somebody could have: zero, not blank.
		assertThat(cell(notices, "STATEMENT_CATEGORY_ANIMAL_WELFARE", AnnualFigures.NOTICES)).isEqualTo("0");

		List<List<String>> rules = parts.get("6_own_initiative_TC");
		// Four refused texts, two refused files, one suspension of moderation's own, and one removal a
		// person decided on what the text check found, which counts as detected by automated means.
		assertThat(cell(rules, "TOTAL", AnnualFigures.MEASURES)).isEqualTo("8");
		assertThat(cell(rules, "TOTAL", AnnualFigures.AUTOMATED)).isEqualTo("7");
		assertThat(cell(rules, "TOTAL", AnnualFigures.SUSPENSION)).isEqualTo("1");
		assertThat(cell(rules, "TOTAL", AnnualFigures.REMOVAL)).isEqualTo("1");
		assertThat(cell(rules, "STATEMENT_CATEGORY_VIOLENCE", AnnualFigures.AUTOMATED)).isEqualTo("5");
		// Hinata never demotes: the column cannot apply, so it stays blank.
		assertThat(cell(rules, "TOTAL", "Visibility restriction Demoted")).isEmpty();
		List<List<String>> others = rows(rules, "KEYWORD_OTHER").stream()
				.filter(row -> !row.get(4).isBlank()).toList();
		assertThat(others).extracting(row -> row.get(4)).contains("Other breaches of the house rules",
				"Files of a type the instance does not accept");

		List<List<String>> appeals = parts.get("7_appeals_recidivism");
		assertThat(indicator(appeals, "Number of complaints submitted to the internal-complaints mechanism",
				"Total number")).isEqualTo("1");
		assertThat(indicator(appeals, "Number of complaints submitted to the internal-complaints mechanism",
				"Decisions reversed")).isEqualTo("1");
		assertThat(indicator(appeals, "Number of complaints submitted to the internal-complaints mechanism",
				"Median time")).isEqualTo("12");

		List<List<String>> identification = parts.get("1_report_identification");
		assertThat(identification.get(1).get(1)).isEqualTo("Hinata HYPERLINK(\"http://evil\")");
		assertThat(identification.get(1).get(3)).isEqualTo("Werkhaus e. V.");
		assertThat(identification.get(2).get(3)).isEqualTo("2027-01-15");
		assertThat(identification.get(4).get(3)).isEqualTo("2026-01-01");
	}

	private static String cell(List<List<String>> part, String key, String column) {
		int index = TransparencyTemplate.column(part, column);
		return rows(part, key).getFirst().get(index);
	}

	private static List<List<String>> rows(List<List<String>> part, String key) {
		return part.stream().filter(row -> row.size() > 3 && row.get(3).strip().equals(key)).toList();
	}

	private static String indicator(List<List<String>> part, String indicator, String scope) {
		int value = TransparencyTemplate.column(part, "Value");
		return part.stream().filter(row -> row.get(4).strip().equals(indicator) && row.get(5).strip().equals(scope))
				.findFirst().orElseThrow().get(value);
	}

	/** The rows Hinata added, each a further "Not captured by any other sub-category" with a description. */
	private static List<List<String>> withoutAddedOthers(List<List<String>> rows, List<String> head) {
		int key = head.size() > 3 ? 3 : -1;
		List<List<String>> kept = new ArrayList<>();
		for (List<String> row : rows) {
			boolean added = key >= 0 && !kept.isEmpty() && "KEYWORD_OTHER".equals(row.get(key).strip())
					&& "KEYWORD_OTHER".equals(kept.getLast().get(key).strip()) && !row.get(4).isBlank()
					&& head.get(4).startsWith("Description of the sub-category");
			if (!added) {
				kept.add(row);
			}
		}
		return kept;
	}

	private static List<List<String>> official(String part) throws IOException {
		try (InputStream in = AnnualFiguresTemplateTest.class
				.getResourceAsStream("/moderation/transparency-template/" + part + ".csv")) {
			return TransparencyCsv.read(in);
		}
	}

	private static Map<String, List<List<String>>> unzip(byte[] zip) throws IOException {
		Map<String, List<List<String>>> parts = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				parts.put(entry.getName(), TransparencyCsv.read(new ByteArrayInputStream(in.readAllBytes())));
			}
		}
		return parts;
	}
}
