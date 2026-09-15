package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.Characters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How many labels an issue may carry, and how long a label may be. The labels of a board's issues
 * make up the board's filter, so what one person writes reaches everybody who opens the board. Every
 * label is also part of the issue's search text, which three board indexes hold as a key: twenty
 * labels of fifty characters keep that key near a kilobyte, where a hundred of a hundred made it ten.
 */
public final class IssueLabels {

	/** The most labels one issue carries. */
	public static final int MAX_LABELS = 20;

	/** The longest label a person may write, in characters as someone reads them, as the app's field counts. */
	public static final int MAX_LENGTH = 50;

	/**
	 * The most UTF-16 units a label takes however few characters they make. One character can take many, as
	 * an emoji of a family takes eleven, and the search text holds every one of them.
	 */
	public static final int MAX_UNITS = 200;

	private IssueLabels() {
	}

	/** [labels] without the blank ones, each once, in the order they were first written. */
	public static List<String> distinct(Collection<String> labels) {
		Set<String> distinct = new LinkedHashSet<>();
		if (labels != null) {
			for (String label : labels) {
				if (label != null && !label.isBlank()) {
					distinct.add(label);
				}
			}
		}
		return new ArrayList<>(distinct);
	}

	/** Whether [label] is longer than a person may write a label. */
	public static boolean tooLong(String label) {
		return label.length() > MAX_UNITS || Characters.count(label) > MAX_LENGTH;
	}

	/**
	 * Refuses [written] when it gains a label [carried] did not hold while it holds more than
	 * {@link #MAX_LABELS} different ones, or when it gains a label that is {@link #tooLong}. A label counts
	 * once however often it is written. What an issue carries already stays, so an issue written before
	 * these limits can still be edited and trimmed, but gains nothing past them.
	 *
	 * @throws ApiException 400 {@code error.issue.labels}
	 */
	public static void check(Collection<String> carried, Collection<String> written) {
		if (written == null) {
			return;
		}
		Set<String> before = carried == null ? Set.of() : new HashSet<>(carried);
		List<String> labels = distinct(written);
		if (labels.size() > MAX_LABELS && labels.stream().anyMatch(label -> !before.contains(label))
				|| labels.stream().anyMatch(label -> !before.contains(label) && tooLong(label))) {
			throw ApiException.badRequest("error.issue.labels", MAX_LABELS, MAX_LENGTH);
		}
	}
}
