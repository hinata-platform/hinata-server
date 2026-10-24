package com.ahmadre.hinata.moderation;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The key a counter of the yearly figures is kept under (HIN-178): the parts the template asks
 * for, joined by colons, in one order for every metric. What it is about comes first (a category,
 * a complaint), then on which ground and how it was found, then what came of it.
 * {@link ModerationStats} writes them and {@link AnnualFigures} reads them, both through here, so
 * a renamed constant or a swapped part breaks a test instead of turning a figure into a quiet zero.
 */
record StatKey(List<String> parts) {

	private static final String SEPARATOR = ":";

	/** The key of [parts]: a constant by its name, anything else, such as hours, as it reads. */
	static String of(Object... parts) {
		return Arrays.stream(parts).map(part -> part instanceof Enum<?> constant ? constant.name() : String.valueOf(part))
				.collect(Collectors.joining(SEPARATOR));
	}

	static StatKey parse(String key) {
		return new StatKey(List.of(key.split(SEPARATOR, -1)));
	}

	/** Part [index] as a constant of [type]; null when the key has none or this version knows no such name. */
	<E extends Enum<E>> E part(int index, Class<E> type) {
		String part = part(index);
		if (part == null) {
			return null;
		}
		try {
			return Enum.valueOf(type, part);
		}
		catch (IllegalArgumentException unknown) {
			return null;
		}
	}

	/** Part [index] as written; null when the key has none. */
	String part(int index) {
		return index < parts.size() ? parts.get(index) : null;
	}
}
