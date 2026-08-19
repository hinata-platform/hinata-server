package com.ahmadre.hinata.issue.export;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * The machine-readable export: one {@code <issue>} document against the schema
 * in {@code issue-export-v1.xsd}.
 *
 * <p>Versioned in the root element from the first release, because the moment
 * somebody scripts against this it is a contract. Adding an element leaves
 * {@code version="1"} standing — a consumer that reads the elements it knows by
 * name keeps working — while renaming or removing one breaks those consumers
 * and is a {@code version="2"}.
 *
 * <p>Written by hand rather than through a marshaller: the document is a page of
 * elements, and a hand-written writer emits no DTD, no processing instruction
 * and no external entity — the three things an XML export has no business
 * containing. Everything that goes in is escaped by {@link ExportText#forXml}.
 */
@Component
class XmlIssueExportRenderer implements IssueExportRenderer {

	/**
	 * Bumped only when an element is renamed or removed, never when one is added.
	 * Consumers that validate rather than read by name are the exception: the
	 * schema file is updated in place for an addition, so one holding an older
	 * copy of it rejects the newer document until it takes the new copy.
	 */
	private static final String SCHEMA_VERSION = "1";

	@Override
	public IssueExportFormat format() {
		return IssueExportFormat.XML;
	}

	@Override
	public byte[] render(IssueExport export) {
		StringBuilder xml = new StringBuilder(4096);
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append("<issue version=\"").append(SCHEMA_VERSION).append("\" key=\"")
				.append(ExportText.forXml(export.readableId())).append("\">\n");
		element(xml, 1, "title", export.title());
		element(xml, 1, "project", export.project());
		element(xml, 1, "organization", export.organization());
		element(xml, 1, "generatedAt", ExportText.DATE_TIME.format(export.generatedAt()));

		xml.append("\t<fields>\n");
		for (IssueExport.Field field : export.fields()) {
			xml.append("\t\t<field name=\"").append(ExportText.forXml(field.label())).append("\">")
					.append(ExportText.forXml(field.value())).append("</field>\n");
		}
		xml.append("\t</fields>\n");

		xml.append("\t<description>\n");
		blocks(xml, 2, export.description());
		xml.append("\t</description>\n");

		xml.append("\t<comments count=\"").append(export.comments().size()).append("\">\n");
		for (IssueExport.Comment comment : export.comments()) {
			xml.append("\t\t<comment author=\"").append(ExportText.forXml(comment.author()))
					.append("\" at=\"").append(instant(comment.at())).append("\">\n");
			blocks(xml, 3, comment.body());
			xml.append("\t\t</comment>\n");
		}
		xml.append("\t</comments>\n");

		xml.append("\t<links count=\"").append(export.links().size()).append("\">\n");
		for (IssueExport.Link link : export.links()) {
			xml.append("\t\t<link verb=\"").append(ExportText.forXml(link.verb()))
					.append("\" key=\"").append(ExportText.forXml(link.readableId())).append("\">")
					.append(ExportText.forXml(link.title())).append("</link>\n");
		}
		xml.append("\t</links>\n");

		xml.append("\t<attachments count=\"").append(export.attachments().size()).append("\">\n");
		for (IssueExport.Attachment file : export.attachments()) {
			xml.append("\t\t<attachment name=\"").append(ExportText.forXml(file.fileName()))
					.append("\" contentType=\"").append(ExportText.forXml(file.contentType()))
					.append("\" size=\"").append(ExportText.forXml(file.size()))
					.append("\" uploader=\"").append(ExportText.forXml(file.uploader()))
					.append("\" uploadedAt=\"").append(instant(file.uploadedAt()))
					.append("\"/>\n");
		}
		xml.append("\t</attachments>\n");

		xml.append("\t<activity count=\"").append(export.activity().size()).append("\">\n");
		for (IssueExport.Activity entry : export.activity()) {
			xml.append("\t\t<entry at=\"").append(ExportText.forXml(entry.at()))
					.append("\" actor=\"").append(ExportText.forXml(entry.actor())).append("\">")
					.append(ExportText.forXml(entry.what())).append("</entry>\n");
		}
		xml.append("\t</activity>\n");

		xml.append("</issue>\n");
		return xml.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * The description's blocks, keeping their kind. A consumer that only wants
	 * the words reads the text and ignores the element names; one that wants the
	 * shape has it without parsing markdown a second time.
	 */
	private static void blocks(StringBuilder xml, int depth, List<ExportBlock> blocks) {
		for (ExportBlock block : blocks) {
			switch (block) {
				case ExportBlock.Heading heading -> {
					indent(xml, depth);
					xml.append("<heading level=\"").append(heading.level()).append("\">")
							.append(ExportText.forXml(ExportBlock.Span.plain(heading.spans())))
							.append("</heading>\n");
				}
				case ExportBlock.Paragraph paragraph ->
						element(xml, depth, "p", ExportBlock.Span.plain(paragraph.spans()));
				case ExportBlock.BulletList list -> {
					indent(xml, depth);
					xml.append("<list ordered=\"").append(list.ordered()).append("\">\n");
					for (List<ExportBlock.Span> item : list.items()) {
						element(xml, depth + 1, "item", ExportBlock.Span.plain(item));
					}
					indent(xml, depth);
					xml.append("</list>\n");
				}
				case ExportBlock.Code code -> {
					indent(xml, depth);
					xml.append("<code language=\"").append(ExportText.forXml(code.language()))
							.append("\">").append(ExportText.forXml(code.text()))
							.append("</code>\n");
				}
				case ExportBlock.Quote quote ->
						element(xml, depth, "quote", ExportBlock.Span.plain(quote.spans()));
				case ExportBlock.Table table -> {
					indent(xml, depth);
					xml.append("<table>\n");
					indent(xml, depth + 1);
					xml.append("<row header=\"true\">\n");
					for (String header : table.headers()) {
						element(xml, depth + 2, "cell", header);
					}
					indent(xml, depth + 1);
					xml.append("</row>\n");
					for (List<String> row : table.rows()) {
						indent(xml, depth + 1);
						xml.append("<row>\n");
						for (String cell : row) {
							element(xml, depth + 2, "cell", cell);
						}
						indent(xml, depth + 1);
						xml.append("</row>\n");
					}
					indent(xml, depth);
					xml.append("</table>\n");
				}
				case ExportBlock.Rule ignored -> {
					indent(xml, depth);
					xml.append("<rule/>\n");
				}
			}
		}
	}

	private static void element(StringBuilder xml, int depth, String name, String text) {
		indent(xml, depth);
		xml.append('<').append(name).append('>').append(ExportText.forXml(text))
				.append("</").append(name).append(">\n");
	}

	private static void indent(StringBuilder xml, int depth) {
		xml.append("\t".repeat(depth));
	}

	private static String instant(Instant value) {
		return value == null ? "" : ExportText.forXml(ExportText.DATE_TIME.format(value));
	}
}
