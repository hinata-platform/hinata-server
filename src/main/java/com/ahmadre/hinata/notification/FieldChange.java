package com.ahmadre.hinata.notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One field of an issue that changed, as raw as {@link IssueChangeDiff} saw it:
 * the field id, the value before, the value after — both as plain strings.
 *
 * <p>Deliberately <em>not</em> pre-rendered. One change reaches recipients who
 * read different languages, and a bundled change can sit in the digest queue for
 * half an hour before anyone turns it into a sentence, so the wording is decided
 * at send time by {@link IssueChangeRenderer}. Storing "Priorität: NORMAL →
 * MAJOR" here would mail German to an English reader — and would freeze a
 * display name, a sprint name or a due-date format that has since changed.
 */
public record FieldChange(String field, String oldValue, String newValue) {

	/**
	 * Folds repeated edits of the same field into a single entry: the value it
	 * started at, the value it ended at. A field that was changed and changed back
	 * disappears entirely — nobody wants a mail announcing that a due date moved
	 * when it reads exactly as it did before.
	 *
	 * <p>The order of first appearance is kept, so a collapsed list still reads in
	 * the order the work happened rather than in some map's iteration order.
	 */
	public static List<FieldChange> collapse(List<FieldChange> changes) {
		if (changes == null || changes.isEmpty()) return List.of();
		Map<String, FieldChange> byField = new LinkedHashMap<>();
		for (FieldChange change : changes) {
			if (change == null || change.field() == null) continue;
			FieldChange first = byField.get(change.field());
			byField.put(change.field(), first == null
					? change
					: new FieldChange(change.field(), first.oldValue(), change.newValue()));
		}
		List<FieldChange> collapsed = new ArrayList<>();
		for (FieldChange change : byField.values()) {
			// A valueless field (a description edit) carries no before/after to
			// compare, so "it ended where it started" cannot be read off it; it is
			// only ever recorded when the stored document really did differ.
			if (IssueChangeDiff.valueless(change.field())
					|| !Objects.equals(change.oldValue(), change.newValue())) {
				collapsed.add(change);
			}
		}
		return collapsed;
	}
}
