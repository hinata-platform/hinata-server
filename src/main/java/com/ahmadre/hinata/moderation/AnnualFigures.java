package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.moderation.TransparencyCategories.Placement;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The yearly figures of moderation (DSA Art. 15, HIN-178) in the Commission's template:
 * Implementing Regulation (EU) 2024/2835, Annex I, filled as Annex II instructs. One CSV per part
 * that applies to a hosting service, in a ZIP; parts 9 and 10 apply to very large platforms only.
 *
 * <p>Read from the counters of the year alone, never from cases, which are deleted long before
 * the year ends. No names, no content, no rankings: every value is a number, a date or a name the
 * template defines. A row that cannot apply to Hinata stays blank; one that applies but saw
 * nothing says 0, as the instructions ask. What Hinata does not record (orders of authorities,
 * the free text of part 11) stays blank for the operator to fill in.
 */
@Service
@RequiredArgsConstructor
public class AnnualFigures {

	static final String TEMPLATES = "moderation/transparency/";

	/** The parts of the template a hosting service publishes, by their file name. */
	static final List<String> PARTS = List.of("1_report_identification", "2_categories_names",
			"3_member_states_orders", "4_notices", "5_own_initiative_illegal", "6_own_initiative_TC",
			"7_appeals_recidivism", "8_automated_means", "11_qualitative");

	/** Hours past this many are counted as one more: the template wants a number. */
	private static final long OVERFLOW_HOURS = ModerationStats.HOURS_COUNTED + 1L;

	// Section 4: notices.
	static final String NOTICES = "Number of notices received";
	static final String NOTICE_ITEMS = "Number of specific items of information included in the total number of notices";
	static final String MEDIAN_ACTION = "Median time to take action";
	static final String LAW_ACTIONS = "Number of actions taken on the basis of the law";
	static final String RULES_ACTIONS = "Number of actions taken on the basis of the terms and conditions of the service";

	// Sections 5 and 6: measures on the provider's own initiative.
	static final String MEASURES = "Number of measures taken at the provider's own initiative";
	static final String AUTOMATED = "Number of measures taken after detection with solely automated means";
	static final String REMOVAL = "Visibility restriction Removal";
	static final String VISIBILITY_OTHER = "Visibility restriction Other";
	static final String SUSPENSION = "Account restriction Suspension";
	static final String TERMINATION = "Account restriction Termination";

	private static final String TOTAL = "TOTAL";

	private final MongoTemplate mongo;
	private final SettingsService settings;
	private final Clock clock;

	/** The figures of [year] as a ZIP of the template's parts. */
	public byte[] zip(int year) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, List<List<String>>> part : parts(year).entrySet()) {
				zip.putNextEntry(new ZipEntry(part.getKey() + ".csv"));
				zip.write(TransparencyCsv.write(part.getValue()));
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	/** Each part of [year] by its file name, filled. */
	Map<String, List<List<String>>> parts(int year) {
		Counts counts = counts(year);
		Head head = head(year);
		Map<String, List<List<String>>> parts = new LinkedHashMap<>();
		parts.put("1_report_identification", identification(template("1_report_identification"), head));
		parts.put("2_categories_names", categoryNames(template("2_categories_names")));
		parts.put("3_member_states_orders", stamped(template("3_member_states_orders"), head));
		parts.put("4_notices", notices(template("4_notices"), head, counts));
		parts.put("5_own_initiative_illegal", ownInitiative(template("5_own_initiative_illegal"), head, counts,
				Decision.Ground.LAW));
		parts.put("6_own_initiative_TC", ownInitiative(template("6_own_initiative_TC"), head, counts,
				Decision.Ground.RULES));
		parts.put("7_appeals_recidivism", appeals(template("7_appeals_recidivism"), head, counts));
		parts.put("8_automated_means", automated(template("8_automated_means"), head, counts));
		parts.put("11_qualitative", stamped(template("11_qualitative"), head));
		return parts;
	}

	// --- the counters ------------------------------------------------------------------------

	/** Every counter of one year, by metric and key: a few thousand small documents at most. */
	private record Counts(Map<StatMetric, Map<String, Long>> byMetric) {

		Map<String, Long> of(StatMetric metric) {
			return byMetric.getOrDefault(metric, Map.of());
		}

		long get(StatMetric metric, String key) {
			return of(metric).getOrDefault(key, 0L);
		}

		long total(StatMetric metric) {
			return of(metric).values().stream().mapToLong(Long::longValue).sum();
		}
	}

	private Counts counts(int year) {
		Map<StatMetric, Map<String, Long>> byMetric = new EnumMap<>(StatMetric.class);
		for (ModerationStat stat : mongo.find(Query.query(Criteria.where("year").is(year)), ModerationStat.class)) {
			if (stat.getMetric() != null && stat.getKey() != null) {
				byMetric.computeIfAbsent(stat.getMetric(), metric -> new HashMap<>())
						.merge(stat.getKey(), stat.getCount(), Long::sum);
			}
		}
		return new Counts(byMetric);
	}

	// --- the head every part carries --------------------------------------------------------

	/** The service, its provider, the period, and the day of the report. */
	private record Head(String service, String provider, String period, LocalDate from, LocalDate until,
			LocalDate published) {
	}

	private Head head(int year) {
		ServerSettings stored = settings.get();
		String service = cell(stored == null ? null : stored.getOrganizationName());
		String provider = cell(stored == null || stored.getModeration() == null ? null
				: stored.getModeration().getOperatorName());
		LocalDate from = LocalDate.of(year, 1, 1);
		LocalDate until = LocalDate.of(year, 12, 31);
		return new Head(service.isEmpty() ? "Hinata" : "Hinata " + service, provider, from + "/" + until, from,
				until, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
	}

	/**
	 * [text] fit for a cell: a name an administrator typed never starts a formula in a
	 * spreadsheet that opens the file, and stays on one line.
	 */
	static String cell(String text) {
		if (text == null) {
			return "";
		}
		String plain = text.replaceAll("[\\r\\n\\t]+", " ").strip();
		while (!plain.isEmpty() && "=+-@".indexOf(plain.charAt(0)) >= 0) {
			plain = plain.substring(1).strip();
		}
		return plain.length() > 200 ? plain.substring(0, 200) : plain;
	}

	// --- part by part ---------------------------------------------------------------------------

	private List<List<String>> identification(List<List<String>> template, Head head) {
		Map<String, String> values = Map.of(
				"Name of the service provider", head.provider(),
				"Date of the publication of the report", head.published().toString(),
				"Date of the publication of the latest previous report", "",
				"Starting date of reporting period", head.from().toString(),
				"Ending date of reporting period", head.until().toString());
		List<List<String>> filled = new ArrayList<>();
		filled.add(template.getFirst());
		int service = column(template, "Service");
		int indicator = column(template, "Indicator");
		int value = column(template, "Value");
		for (List<String> row : template.subList(1, template.size())) {
			List<String> copy = new ArrayList<>(row);
			copy.set(service, head.service());
			copy.set(value, values.getOrDefault(row.get(indicator).strip(), ""));
			filled.add(copy);
		}
		return filled;
	}

	private List<List<String>> categoryNames(List<List<String>> template) {
		Map<String, String> contextual = TransparencyCategories.contextual();
		int key = column(template, "Category of illegal content / incompatible with the terms and conditions");
		int context = column(template, "Contextual information");
		List<List<String>> filled = new ArrayList<>();
		filled.add(template.getFirst());
		for (List<String> row : template.subList(1, template.size())) {
			List<String> copy = new ArrayList<>(row);
			copy.set(context, contextual.getOrDefault(row.get(key).strip(), ""));
			filled.add(copy);
		}
		return filled;
	}

	/** A part Hinata has nothing to count for: the service and the period, the rest left to fill in. */
	private List<List<String>> stamped(List<List<String>> template, Head head) {
		int service = column(template, "Service");
		int period = column(template, "Reporting period");
		List<List<String>> filled = new ArrayList<>();
		filled.add(template.getFirst());
		for (List<String> row : template.subList(1, template.size())) {
			List<String> copy = new ArrayList<>(row);
			copy.set(service, head.service());
			copy.set(period, head.period());
			filled.add(copy);
		}
		return filled;
	}

	private List<List<String>> notices(List<List<String>> template, Head head, Counts counts) {
		Map<Placement, Figures> placed = new LinkedHashMap<>();
		for (Map.Entry<String, Long> notice : counts.of(StatMetric.NOTICE).entrySet()) {
			ReportCategory category = StatKey.parse(notice.getKey()).part(0, ReportCategory.class);
			if (category == null) {
				continue;
			}
			Figures figures = placed.computeIfAbsent(TransparencyCategories.notice(category), p -> new Figures());
			figures.add(NOTICES, notice.getValue());
			// One notice names one item: a report is about one comment, issue, file, page or person.
			figures.add(NOTICE_ITEMS, notice.getValue());
		}
		for (Map.Entry<String, Long> action : counts.of(StatMetric.NOTICE_ACTION).entrySet()) {
			StatKey key = StatKey.parse(action.getKey());
			ReportCategory category = key.part(0, ReportCategory.class);
			Decision.Ground ground = key.part(1, Decision.Ground.class);
			if (category == null || ground == null) {
				continue;
			}
			placed.computeIfAbsent(TransparencyCategories.notice(category), p -> new Figures())
					.add(ground == Decision.Ground.LAW ? LAW_ACTIONS : RULES_ACTIONS, action.getValue());
		}
		for (Map.Entry<String, Long> hours : counts.of(StatMetric.NOTICE_HOURS).entrySet()) {
			StatKey key = StatKey.parse(hours.getKey());
			ReportCategory category = key.part(0, ReportCategory.class);
			if (category == null || key.part(1) == null) {
				continue;
			}
			placed.computeIfAbsent(TransparencyCategories.notice(category), p -> new Figures())
					.hours(key.part(1), hours.getValue());
		}
		return categories(template, head, placed, Figures.sum(placed.values()),
				Set.of(NOTICES, NOTICE_ITEMS, LAW_ACTIONS, RULES_ACTIONS), MEDIAN_ACTION);
	}

	private List<List<String>> ownInitiative(List<List<String>> template, Head head, Counts counts,
			Decision.Ground ground) {
		boolean law = ground == Decision.Ground.LAW;
		Map<Placement, Figures> placed = new LinkedHashMap<>();
		Figures total = new Figures();
		for (Map.Entry<String, Long> measure : counts.of(StatMetric.OWN_MEASURE).entrySet()) {
			StatKey key = StatKey.parse(measure.getKey());
			ReportCategory category = key.part(0, ReportCategory.class);
			if (category == null || key.part(1, Decision.Ground.class) != ground) {
				continue;
			}
			// A person decided, on what the text check found: detected by automated means alone.
			boolean detected = key.part(2, Decision.Basis.class) == Decision.Basis.AUTOMATED_DETECTION;
			total.add(MEASURES, measure.getValue());
			if (detected) {
				total.add(AUTOMATED, measure.getValue());
			}
			Placement placement = placementOf(category, law);
			if (placement != null) {
				Figures figures = placed.computeIfAbsent(placement, p -> new Figures());
				figures.add(MEASURES, measure.getValue());
				if (detected) {
					figures.add(AUTOMATED, measure.getValue());
				}
			}
		}
		for (Map.Entry<String, Long> restriction : counts.of(StatMetric.OWN_RESTRICTION).entrySet()) {
			StatKey key = StatKey.parse(restriction.getKey());
			ReportCategory category = key.part(0, ReportCategory.class);
			ModerationStats.Restriction kind = key.part(2, ModerationStats.Restriction.class);
			if (category == null || kind == null || key.part(1, Decision.Ground.class) != ground) {
				continue;
			}
			String column = switch (kind) {
				case REMOVAL -> REMOVAL;
				case SUSPENSION -> SUSPENSION;
				case TERMINATION -> TERMINATION;
				case OTHER -> VISIBILITY_OTHER;
			};
			total.add(column, restriction.getValue());
			Placement placement = placementOf(category, law);
			if (placement != null) {
				placed.computeIfAbsent(placement, p -> new Figures()).add(column, restriction.getValue());
			}
		}
		if (!law) {
			// What the text check and the upload gate refused is a measure of the provider's own,
			// taken by automated means alone, on the ground of the house rules.
			for (Map.Entry<String, Long> refused : counts.of(StatMetric.TEXT_REFUSED).entrySet()) {
				ReportCategory category = StatKey.parse(refused.getKey()).part(0, ReportCategory.class);
				Placement placement = TransparencyCategories.termsAndConditions(
						category == null ? ReportCategory.OTHER : category);
				refusals(placed.computeIfAbsent(placement, p -> new Figures()), refused.getValue());
				refusals(total, refused.getValue());
			}
			long uploads = counts.total(StatMetric.UPLOAD_REFUSED);
			if (uploads > 0) {
				refusals(placed.computeIfAbsent(TransparencyCategories.FILE_TYPES, p -> new Figures()), uploads);
				refusals(total, uploads);
			}
		}
		return categories(template, head, placed, total,
				Set.of(MEASURES, AUTOMATED, REMOVAL, VISIBILITY_OTHER, SUSPENSION, TERMINATION), null);
	}

	/** The years with figures, and this one: what the admin area offers to download. */
	public List<Integer> years() {
		int current = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).getYear();
		TreeSet<Integer> years = new TreeSet<>(Comparator.reverseOrder());
		years.add(current);
		for (Object year : mongo.getCollection(mongo.getCollectionName(ModerationStat.class))
				.distinct("year", Integer.class)) {
			if (year instanceof Integer known && known <= current) {
				years.add(known);
			}
		}
		return List.copyOf(years);
	}

	private static Placement placementOf(ReportCategory category, boolean law) {
		return law ? TransparencyCategories.illegal(category) : TransparencyCategories.termsAndConditions(category);
	}

	private static void refusals(Figures figures, long count) {
		figures.add(MEASURES, count);
		figures.add(AUTOMATED, count);
		figures.add(VISIBILITY_OTHER, count);
	}

	private List<List<String>> appeals(List<List<String>> template, Head head, Counts counts) {
		String internal = "Number of complaints submitted to the internal-complaints mechanism";
		String removal = "Complaint regarding a decision to remove or disable access to or restrict visibility of information";
		String account = "Complaint regarding a decision to suspend or terminate an account";
		Map<String, String> values = new HashMap<>();
		values.put(key(internal, "Total number"), String.valueOf(counts.total(StatMetric.APPEAL_FILED)));
		long upheld = 0;
		long reversed = 0;
		for (ModerationStats.Complaint complaint : ModerationStats.Complaint.values()) {
			String indicator = complaint == ModerationStats.Complaint.REMOVAL ? removal : account;
			long upheldOf = counts.get(StatMetric.APPEAL_RESULT,
					StatKey.of(complaint, AppealService.AppealDecisionRequest.Result.UPHOLD));
			long reversedOf = counts.get(StatMetric.APPEAL_RESULT,
					StatKey.of(complaint, AppealService.AppealDecisionRequest.Result.REVERSE));
			upheld += upheldOf;
			reversed += reversedOf;
			values.put(key(indicator, "Total number"), String.valueOf(counts.get(StatMetric.APPEAL_FILED, complaint.name())));
			values.put(key(indicator, "Decisions upheld"), String.valueOf(upheldOf));
			values.put(key(indicator, "Decisions partially reversed"), "0");
			values.put(key(indicator, "Decisions reversed"), String.valueOf(reversedOf));
			values.put(key(indicator, "Median time"), median(hoursOf(counts.of(StatMetric.APPEAL_HOURS), complaint)));
		}
		values.put(key(internal, "Decisions upheld"), String.valueOf(upheld));
		values.put(key(internal, "Decisions partially reversed"), "0");
		values.put(key(internal, "Decisions reversed"), String.valueOf(reversed));
		values.put(key(internal, "Median time"), median(hoursOf(counts.of(StatMetric.APPEAL_HOURS), null)));
		// Hinata decides every appeal it is given and never imposes anything new through one.
		values.put(key(internal, "Decision omitted"), "0");
		values.put(key("Number of restrictions newly imposed as a result of an internal complaint", "Total number"), "0");
		return indicators(template, head, values);
	}

	private List<List<String>> automated(List<List<String>> template, Head head, Counts counts) {
		String solely = "Number of measures solely taken by automated means";
		String not = "Number of measures not taken by automated means";
		long refusals = counts.total(StatMetric.TEXT_REFUSED) + counts.total(StatMetric.UPLOAD_REFUSED);
		long own = counts.total(StatMetric.OWN_MEASURE);
		long decided = own + counts.total(StatMetric.NOTICE_ACTION);
		Map<String, String> values = new HashMap<>();
		values.put(key(solely, "Total number"), String.valueOf(refusals));
		values.put(key(not, "Total number"), String.valueOf(decided));
		values.put(key(solely, "Own-initiative"), String.valueOf(refusals));
		values.put(key(not, "Own-initiative"), String.valueOf(own));
		// No notice is ever decided by a machine: a person looks at every one.
		values.put(key("Number of notices solely processed by automated means", "NAM Total"), "0");
		values.put(key("Number of notices not processed by automated means", "NAM Total"),
				String.valueOf(counts.total(StatMetric.NOTICE)));
		return indicators(template, head, values);
	}

	// --- filling --------------------------------------------------------------------------------

	/** One place's figures in a part by categories: counts by column, and hours for a median. */
	private static final class Figures {

		private final Map<String, Long> counts = new HashMap<>();
		private final Map<String, Long> hours = new HashMap<>();

		void add(String column, long count) {
			counts.merge(column, count, Long::sum);
		}

		void hours(String bucket, long count) {
			hours.merge(bucket, count, Long::sum);
		}

		void addAll(Figures other) {
			other.counts.forEach(this::add);
			other.hours.forEach(this::hours);
		}

		static Figures sum(Iterable<Figures> all) {
			Figures sum = new Figures();
			all.forEach(sum::addAll);
			return sum;
		}
	}

	/**
	 * [template], a part by categories, with every row filled from [placed]: a category as the sum
	 * of its sub-categories, a sub-category from its place, a "Not captured by any other
	 * sub-category" once per description Hinata needs there, [total] in the TOTAL row.
	 */
	private List<List<String>> categories(List<List<String>> template, Head head, Map<Placement, Figures> placed,
			Figures total, Set<String> countColumns, String medianColumn) {
		List<String> header = template.getFirst();
		int service = column(template, "Service");
		int period = column(template, "Reporting period");
		int keyColumn = 3;
		int otherColumn = column(template, "Description of the sub-category \"Other\"");
		Map<Integer, String> counted = new TreeMap<>();
		for (String name : countColumns) {
			counted.put(column(template, name), name);
		}
		Integer median = medianColumn == null ? null : column(template, medianColumn);
		List<List<String>> filled = new ArrayList<>();
		filled.add(header);
		String category = null;
		for (List<String> row : template.subList(1, template.size())) {
			String key = row.get(keyColumn).strip();
			List<Figures> rows = new ArrayList<>();
			List<String> descriptions = new ArrayList<>();
			if (TOTAL.equals(key)) {
				category = key;
				rows.add(total);
				descriptions.add("");
			}
			else if (key.startsWith("STATEMENT_CATEGORY")) {
				category = key;
				String current = key;
				rows.add(Figures.sum(placed.entrySet().stream()
						.filter(entry -> entry.getKey().category().equals(current)).map(Map.Entry::getValue).toList()));
				descriptions.add("");
			}
			else if (TransparencyCategories.OTHER_KEYWORD.equals(key)) {
				String current = category;
				placed.forEach((placement, figures) -> {
					if (placement.category().equals(current) && key.equals(placement.keyword())) {
						rows.add(figures);
						descriptions.add(placement.other());
					}
				});
				if (rows.isEmpty()) {
					rows.add(new Figures());
					descriptions.add("");
				}
			}
			else {
				rows.add(placed.getOrDefault(new Placement(category, key), new Figures()));
				descriptions.add("");
			}
			for (int i = 0; i < rows.size(); i++) {
				List<String> copy = new ArrayList<>(row);
				copy.set(service, head.service());
				copy.set(period, head.period());
				copy.set(otherColumn, descriptions.get(i));
				Figures figures = rows.get(i);
				counted.forEach((index, name) -> copy.set(index, String.valueOf(figures.counts.getOrDefault(name, 0L))));
				if (median != null) {
					copy.set(median, median(figures.hours));
				}
				filled.add(copy);
			}
		}
		return filled;
	}

	/** [template], a part of indicators, with the values of [values] by indicator and scope. */
	private List<List<String>> indicators(List<List<String>> template, Head head, Map<String, String> values) {
		int service = column(template, "Service");
		int period = column(template, "Reporting period");
		int indicator = column(template, "Indicator");
		int scope = column(template, "Scope");
		int value = column(template, "Value");
		List<List<String>> filled = new ArrayList<>();
		filled.add(template.getFirst());
		for (List<String> row : template.subList(1, template.size())) {
			List<String> copy = new ArrayList<>(row);
			copy.set(service, head.service());
			copy.set(period, head.period());
			copy.set(value, values.getOrDefault(key(row.get(indicator), row.get(scope)), ""));
			filled.add(copy);
		}
		return filled;
	}

	private static String key(String indicator, String scope) {
		return indicator.strip() + "|" + scope.strip();
	}

	/** The median of a histogram of hours, the lower one of two in the middle; blank for none. */
	static String median(Map<String, Long> hours) {
		TreeMap<Long, Long> sorted = new TreeMap<>();
		hours.forEach((bucket, count) -> sorted.merge(
				ModerationStats.OVERFLOW.equals(bucket) ? OVERFLOW_HOURS : Long.parseLong(bucket), count, Long::sum));
		long total = sorted.values().stream().mapToLong(Long::longValue).sum();
		if (total == 0) {
			return "";
		}
		long middle = (total + 1) / 2;
		long seen = 0;
		for (Map.Entry<Long, Long> entry : sorted.entrySet()) {
			seen += entry.getValue();
			if (seen >= middle) {
				return String.valueOf(entry.getKey());
			}
		}
		return String.valueOf(sorted.lastKey());
	}

	/** The hours buckets of [counts], keyed {@code COMPLAINT:HOURS}, of [complaint] or of all when null. */
	private static Map<String, Long> hoursOf(Map<String, Long> counts, ModerationStats.Complaint complaint) {
		Map<String, Long> buckets = new HashMap<>();
		counts.forEach((raw, count) -> {
			StatKey key = StatKey.parse(raw);
			if (key.part(1) != null && (complaint == null || key.part(0, ModerationStats.Complaint.class) == complaint)) {
				buckets.merge(key.part(1), count, Long::sum);
			}
		});
		return buckets;
	}

	/** The index of the column named [name] in [template]'s head; a template without it is a fault. */
	static int column(List<List<String>> template, String name) {
		List<String> header = template.getFirst();
		for (int i = 0; i < header.size(); i++) {
			if (header.get(i).strip().equals(name)) {
				return i;
			}
		}
		throw new IllegalStateException("The template has no column " + name);
	}

	/** The Commission's template of part [name], as it ships with Hinata. */
	static List<List<String>> template(String name) {
		try (InputStream in = new ClassPathResource(TEMPLATES + name + ".csv").getInputStream()) {
			return TransparencyCsv.read(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
