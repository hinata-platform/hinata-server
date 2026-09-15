package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;

import java.util.Collection;
import java.util.HashSet;
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

	/** The longest label a person may write. */
	public static final int MAX_LENGTH = 50;

	private IssueLabels() {
	}

	/**
	 * Refuses [written] when it gains a label [carried] did not hold while it holds more than
	 * {@link #MAX_LABELS}, or when it gains a label longer than {@link #MAX_LENGTH}. What an issue carries
	 * already stays, so an issue written before these limits can still be edited and trimmed, but gains
	 * nothing past them.
	 *
	 * @throws ApiException 400 {@code error.issue.labels}
	 */
	public static void check(Collection<String> carried, Collection<String> written) {
		if (written == null) {
			return;
		}
		Set<String> before = carried == null ? Set.of() : new HashSet<>(carried);
		boolean tooMany = written.size() > MAX_LABELS && written.stream().anyMatch(label -> !before.contains(label));
		boolean tooLong = written.stream()
				.anyMatch(label -> label != null && label.length() > MAX_LENGTH && !before.contains(label));
		if (tooMany || tooLong) {
			throw ApiException.badRequest("error.issue.labels", MAX_LABELS, MAX_LENGTH);
		}
	}
}
