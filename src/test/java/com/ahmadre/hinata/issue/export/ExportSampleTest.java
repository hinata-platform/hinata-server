package com.ahmadre.hinata.issue.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Writes a real exported issue to disk so somebody can look at it.
 *
 * <p>Run it on purpose, the same way the e-mail gallery is:
 *
 * <pre>{@code ./gradlew exportPreview && open hinata-server/build/export-preview}</pre>
 *
 * <p>The assertions in the suite beside this one pin the behaviour; they cannot
 * tell whether the result reads well. A German PDF with a mention in it, a
 * localized "Fälligkeitsdatum" and a stamp on Berlin's clock is the sort of
 * thing that is either obviously right or obviously wrong on sight, and that is
 * worth being able to produce in one command.
 */
class ExportSampleTest {

	private final PdfIssueExportRenderer pdf = new PdfIssueExportRenderer();
	private final DocxIssueExportRenderer docx = new DocxIssueExportRenderer();
	private final XlsxIssueExportRenderer xlsx = new XlsxIssueExportRenderer();
	private final XmlIssueExportRenderer xml = new XmlIssueExportRenderer();

	private static final Path OUT = Path.of("build", "export-preview");

	@Test
	void writeSamples() throws Exception {
		Path out = OUT;
		Files.createDirectories(out);
		write(out, "de", ExportWordsFixture.german());
		write(out, "en", ExportWordsFixture.english());
	}

	private void write(Path out, String tag, ExportWords words) throws Exception {
		IssueExport export = sample(words);
		Files.write(out.resolve("issue-export-" + tag + ".pdf"), pdf.render(export));
		Files.write(out.resolve("issue-export-" + tag + ".docx"), docx.render(export));
		Files.write(out.resolve("issue-export-" + tag + ".xlsx"), xlsx.render(export));
		Files.write(out.resolve("issue-export-" + tag + ".xml"), xml.render(export));
	}

	/**
	 * The issue from the report that started this: an @-mention in the
	 * description, another three in a comment, timestamps in August (so the zone
	 * abbreviation is the summer one), and a due date to prove a date-only value
	 * is formatted without being moved.
	 */
	private static IssueExport sample(ExportWords words) {
		List<ExportBlock> description = SmartLinks.resolve(
				MarkdownBlocks.of("""
						{{user:6a57935ad3fc935407184c97}} hat bereits alle Zugänge und ist Admin wie \
						ich, aber ich muss nochmal in einem Meeting prüfen, ob er alle neu \
						hinzugekommenen Zugänge hat die sich in letzter Zeit ergeben haben.

						OpenSlides hat er schon. Der Ablauf steht in {{doc:6b1}}, die Übergabe \
						selbst hängt an {{issue:IT-88}}."""),
				naming());
		List<ExportBlock> comment = SmartLinks.resolve(
				MarkdownBlocks.of("""
						{{user:6a3d0d9774fd69a89d133e9c}} {{user:6a3d0d9a74fd69a89d133e9f}} \
						{{user:6a4fbbe62ce74c7b4c60832f}}

						Ich plane momentan meinen Austritt, wenn euch noch irgendwas einfällt, \
						schreibt es hier in die Commis oder als Subtask."""),
				naming());
		return new IssueExport(
				"IT-89", "IT-Austritt: Übergabe neuer/restlicher Zugänge, Passwortmanager",
				"IT",
				List.of(
						new IssueExport.Field("type", words.t("export.field.type"),
								words.or("export.type.TASK", "TASK")),
						new IssueExport.Field("status", words.t("export.field.status"), "Done"),
						new IssueExport.Field("priority", words.t("export.field.priority"),
								words.or("export.priority.NORMAL", "NORMAL")),
						new IssueExport.Field("assignees", words.t("export.field.assignees"),
								"Rebar Ahmad"),
						new IssueExport.Field("dueDate", words.t("export.field.dueDate"),
								words.date(java.time.LocalDate.of(2026, 9, 1))),
						new IssueExport.Field("projectKey", words.t("export.field.projectKey"), "IT"),
						new IssueExport.Field("created", words.t("export.field.created"),
								words.instant(Instant.parse("2026-08-18T16:39:00Z"))),
						new IssueExport.Field("updated", words.t("export.field.updated"),
								words.instant(Instant.parse("2026-08-26T22:22:00Z")))),
				description,
				List.of(new IssueExport.Comment("Rebar Ahmad",
						Instant.parse("2026-08-18T16:48:00Z"), comment)),
				List.of(new IssueExport.Link(
						words.or("export.link.BLOCKS.outward", "blocks"), "IT-90",
						"Passwortmanager-Zugänge sichten")),
				List.of(new IssueExport.Attachment("Alice Domke.pdf", "application/pdf", "86 KB",
						"Hicham Wahidi", Instant.parse("2026-08-19T09:12:00Z"))),
				List.of(new IssueExport.Activity(Instant.parse("2026-08-26T22:22:00Z"),
						"Rebar Ahmad", words.t("export.field.status") + ": In Progress → Done")),
				"AStA der Hochschule Niederrhein", null,
				Instant.parse("2026-08-26T23:50:00Z"), words);
	}

	/** The names the sample's tokens stand for. */
	private static java.util.function.BiFunction<String, String, String> naming() {
		return (kind, id) -> switch (kind) {
			case "user" -> "@" + switch (id) {
				case "6a57935ad3fc935407184c97" -> "Hicham Wahidi";
				case "6a3d0d9774fd69a89d133e9c" -> "Alice Domke";
				case "6a3d0d9a74fd69a89d133e9f" -> "Jonas Peters";
				case "6a4fbbe62ce74c7b4c60832f" -> "Mei Tanaka";
				default -> id;
			};
			case "doc" -> "Übergabe-Checkliste IT";
			case "issue" -> id;
			default -> null;
		};
	}
}
