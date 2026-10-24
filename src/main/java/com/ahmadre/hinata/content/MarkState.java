package com.ahmadre.hinata.content;

/** What happened to one field of moderated content. */
public enum MarkState {
	/** The field shows the placeholder. */
	REMOVED,
	/** Somebody wrote the field anew after it was removed; it shows their text again. */
	REWRITTEN
}
