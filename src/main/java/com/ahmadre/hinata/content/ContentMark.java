package com.ahmadre.hinata.content;

import java.time.Instant;

/**
 * The mark one field carries once moderation removed it, stored on the content itself in an
 * optional map from the field's name: {@code moderation: {title: {state, caseId, at}}}. Responses
 * only grow by it, so an app that knows nothing of it shows the placeholder text it finds.
 */
public record ContentMark(MarkState state, String caseId, Instant at) {

	public static ContentMark removed(String caseId) {
		return new ContentMark(MarkState.REMOVED, caseId, Instant.now());
	}

	public ContentMark rewritten() {
		return new ContentMark(MarkState.REWRITTEN, caseId, Instant.now());
	}

	public boolean isRemoved() {
		return state == MarkState.REMOVED;
	}
}
