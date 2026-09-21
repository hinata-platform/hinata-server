package com.ahmadre.hinata.issue.export;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One issue, gathered once and shaped for reading rather than for storage —
 * every value already a string somebody could print, every id already resolved
 * to the name it stands for.
 *
 * <p>The four renderers share this and add nothing to it. That is what keeps a
 * PDF and a Word export of the same issue saying the same thing: the question
 * "what does an export contain" is answered here, once, and the formats only
 * decide how it looks. The app's list export made the same split for the same
 * reason — one row type carrying display text, builders that only place it.
 *
 * @param logo the organization's mark as normalized PNG bytes, or {@code null}.
 *             {@code null} is the ordinary case, not an error: most instances
 *             configure no logo at all, and one configured as a vector cannot be
 *             decoded by this process. Every renderer therefore has to produce
 *             its usual document without it.
 * @param words the language the document is written in and the clock its
 *              timestamps are read on — see {@link ExportWords}. Every value in
 *              the record above has already been through it; this is here for
 *              the few words the renderers own themselves, the headings over
 *              their sections and the headers over their tables.
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
		byte[] logo,
		Instant generatedAt,
		ExportWords words) {

	/**
	 * A labelled value from the issue's head — "Status", "In Progress".
	 *
	 * @param key   the field's stable name ({@code dueDate}), which never changes
	 *              with the reader's language. The formats a person reads print
	 *              [label]; the XML, which a program reads, prints both — a
	 *              consumer that had to match on "Due date" would stop matching
	 *              the moment the same export was taken in German.
	 * @param label [key] in the reader's language
	 */
	public record Field(String key, String label, String value) {
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

	/** [at] stays an instant: each format stamps it its own way, and the XML
	 *  stamps it in a shape a machine can read. */
	public record Activity(Instant at, String actor, String what) {
	}

	/**
	 * This issue as a document for {@link PdfDocumentRenderer} and
	 * {@link DocxDocumentRenderer}: the key above the title, the project under it, the
	 * head as labelled values, then every section that has something in it. The
	 * spreadsheet and the XML keep shapes of their own — a sheet per kind of record and
	 * a published schema — because a program reads them, not a person.
	 */
	public ExportDocument document() {
		List<ExportBlock> blocks = new ArrayList<>();
		List<ExportBlock.KeyValue> head = fields.stream()
				.filter(field -> !field.value().isBlank())
				.map(field -> new ExportBlock.KeyValue(field.label(), field.value()))
				.toList();
		if (!head.isEmpty()) {
			blocks.add(new ExportBlock.Section(words.t("export.section.details")));
			blocks.add(new ExportBlock.KeyValues(head));
		}
		if (!description.isEmpty()) {
			blocks.add(new ExportBlock.Section(words.t("export.section.description")));
			blocks.addAll(description);
		}
		if (!comments.isEmpty()) {
			blocks.add(new ExportBlock.Section(words.t("export.section.comments", comments.size())));
			for (Comment comment : comments) {
				blocks.add(new ExportBlock.Note(comment.author()
						+ (comment.at() == null ? "" : " · " + words.instant(comment.at()))));
				blocks.addAll(comment.body());
			}
		}
		if (!links.isEmpty()) {
			blocks.add(new ExportBlock.Section(words.t("export.section.links")));
			for (Link link : links) {
				blocks.add(new ExportBlock.Paragraph(List.of(
						new ExportBlock.Span(link.verb(), true, false, false, false),
						new ExportBlock.Span("  " + link.readableId() + " " + link.title(),
								false, false, false, false))));
			}
		}
		if (!attachments.isEmpty()) {
			blocks.add(new ExportBlock.Section(words.t("export.section.attachments")));
			blocks.add(new ExportBlock.Table(
					List.of(words.t("export.column.file"), words.t("export.column.type"),
							words.t("export.column.size"), words.t("export.column.uploadedBy")),
					attachments.stream().map(file -> List.of(file.fileName(), file.contentType(),
							file.size(), file.uploader())).toList(),
					List.of(2.2f, 1.4f, 0.7f, 1.2f), Set.of(2)));
		}
		if (!activity.isEmpty()) {
			blocks.add(new ExportBlock.Section(words.t("export.section.history")));
			blocks.add(new ExportBlock.Table(
					List.of(words.t("export.column.date"), words.t("export.column.author"),
							words.t("export.column.change")),
					activity.stream().map(entry -> List.of(words.instant(entry.at()), entry.actor(),
							entry.what())).toList(),
					List.of(1.2f, 1.1f, 2.7f), Set.of()));
		}
		String where = project.isBlank() ? organization
				: project + (organization.isBlank() ? "" : " · " + organization);
		return new ExportDocument(readableId, title, where, blocks, organization, logo, generatedAt, words);
	}

	/** What the caller asked to be included; every section defaults to shown. */
	public record Options(boolean comments, boolean links, boolean attachments,
			boolean activity) {

		/** Every section but the history — what a caller who asks for nothing gets. */
		public static Options standard() {
			return new Options(true, true, true, false);
		}
	}
}
