package com.ahmadre.hinata.issue.export;

import java.time.Instant;
import java.util.List;

/**
 * One issue, gathered once and shaped for reading rather than for storage —
 * every value already a string somebody could print, every id already resolved
 * to the name it stands for.
 *
 * <p>The four renderers share this and add nothing to it. That is what keeps a
 * PDF and a Word export of the same issue saying the same thing: the question
 * "what does an export contain" is answered here, once, and the formats only
 * decide how it looks. The list export made the same split — {@code
 * IssueExportRow} carries display text and its builders stay dumb.
 */
public record IssueExport(
		String readableId,
		String title,
		String project,
		List<Field> fields,
		List<ExportBlock> description,
		List<Comment> comments,
		List<Link> links,
		List<Attachment> attachments,
		List<Activity> activity,
		String organization,
		Instant generatedAt) {

	/** A labelled value from the issue's head — "Status", "In Progress". */
	public record Field(String label, String value) {
	}

	public record Comment(String author, Instant at, List<ExportBlock> body) {
	}

	/** A link as it reads from this issue's side: "blocks", "HIN-42", its title. */
	public record Link(String verb, String readableId, String title) {
	}

	/** Attachment metadata. Never the bytes — an export is a document, not an archive. */
	public record Attachment(String fileName, String contentType, String size,
			String uploader, Instant uploadedAt) {
	}

	public record Activity(String at, String actor, String what) {
	}

	/** What the caller asked to be included; every section defaults to shown. */
	public record Options(boolean comments, boolean links, boolean attachments,
			boolean activity) {

		/** What the endpoints answer with when nothing is asked for explicitly. */
		public static Options standard() {
			return new Options(true, true, true, false);
		}
	}
}
