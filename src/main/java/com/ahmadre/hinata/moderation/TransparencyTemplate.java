package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.moderation.TransparencyCategories.Placement;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Commission's template of the yearly figures (Implementing Regulation (EU) 2024/2835,
 * Annex I) and how it is filled, whatever the figures are: its parts as Hinata ships them, a
 * column by its head, a part of categories filled from its places, a part of indicators from its
 * values, a median in hours. What Hinata counts and where it goes is {@link AnnualFigures}.
 */
final class TransparencyTemplate {

	/** Where the parts of the template ship. */
	static final String TEMPLATES = "moderation/transparency/";

	/** Hours past this many are counted as one more: the template wants a number. */
	private static final long OVERFLOW_HOURS = ModerationStats.HOURS_COUNTED + 1L;

	private static final String TOTAL = "TOTAL";

	private TransparencyTemplate() {
	}

	/** A part Hinata has nothing to count for: the service and the period, the rest left to fill in. */
	static List<List<String>> stamped(List<List<String>> template, String serviceName, String periodText) {
		int service = column(template, "Service");
		int period = column(template, "Reporting period");
		List<List<String>> filled = new ArrayList<>();
		filled.add(template.getFirst());
		for (List<String> row : template.subList(1, template.size())) {
			List<String> copy = new ArrayList<>(row);
			copy.set(service, serviceName);
			copy.set(period, periodText);
			filled.add(copy);
		}
		return filled;
	}

	/** One place's figures in a part by categories: counts by column, and hours for a median. */
	static final class Figures {

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
	static List<List<String>> categories(List<List<String>> template, String serviceName, String periodText,
			Map<Placement, Figures> placed, Figures total, Set<String> countColumns, String medianColumn) {
		List<String> header = template.getFirst();
		int service = column(template, "Service");
		int period = column(template, "Reporting period");
		int keyColumn = keyColumn(template);
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
				copy.set(service, serviceName);
				copy.set(period, periodText);
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
	static List<List<String>> indicators(List<List<String>> template, String serviceName, String periodText,
			Map<String, String> values) {
		int service = column(template, "Service");
		int period = column(template, "Reporting period");
		int indicator = column(template, "Indicator");
		int scope = column(template, "Scope");
		int value = column(template, "Value");
		List<List<String>> filled = new ArrayList<>();
		filled.add(template.getFirst());
		for (List<String> row : template.subList(1, template.size())) {
			List<String> copy = new ArrayList<>(row);
			copy.set(service, serviceName);
			copy.set(period, periodText);
			copy.set(value, values.getOrDefault(indicatorKey(row.get(indicator), row.get(scope)), ""));
			filled.add(copy);
		}
		return filled;
	}

	/** How [values] of {@link #indicators} are keyed: an indicator and its scope. */
	static String indicatorKey(String indicator, String scope) {
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

	/** The column a part by categories names its rows in: its head starts with "Category of". */
	static int keyColumn(List<List<String>> template) {
		List<String> header = template.getFirst();
		for (int i = 0; i < header.size(); i++) {
			if (header.get(i).strip().startsWith("Category of")) {
				return i;
			}
		}
		throw new IllegalStateException("The template has no column of categories");
	}

	/** The Commission's template of part [name], as it ships with Hinata. */
	static List<List<String>> load(String name) {
		try (InputStream in = new ClassPathResource(TEMPLATES + name + ".csv").getInputStream()) {
			return TransparencyCsv.read(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
