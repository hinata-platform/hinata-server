package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.content.ContentRef;

/**
 * Checks text before it is stored (HIN-176). Declared by the core, implemented by moderation;
 * without an implementation everything is allowed. The write services call it through
 * {@link TextCheck}, never the converter: {@link RichTextService} knows neither field nor the
 * stored state, and it also runs where nothing is to be checked.
 *
 * <p>Not checked, on purpose: notes of time tracking and absences (a narrow circle, care rather
 * than a filter); names of projects, teams, boards and sprints (written by responsible roles,
 * reported as "something else"); replies mailed to outside senders (not stored, sent to one
 * person, sizes bounded by HIN-167); copied projects (copies of checked content); restoring a
 * version, and moderation's own placeholders.
 */
public interface TextGate {

	/**
	 * Judges [text] as [field]; [before] is the stored text of the same field, or null for new
	 * content. Only judges: a look at a name that is then replaced counts as nothing.
	 */
	TextVerdict check(String text, TextField field, String before);

	/** A write refused for [verdict], once per write however many of its fields were refused. */
	void refused(TextVerdict verdict);

	/** Called by the write service after saving when [verdict] opens a case: moderation files it on [ref]. */
	void flagged(ContentRef ref, TextField field, TextVerdict verdict);
}
