package com.ahmadre.hinata.richtext;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the cross-language wire-format corpus.
 *
 * <p>The server converts markdown to Lexical and the Flutter app renders the
 * result, which makes the document shape a contract between two codebases in two
 * languages. Unit tests on either side only prove that side is self-consistent;
 * a document the server emits and the app cannot open would pass both.
 *
 * <p>So this writes one corpus, from the real converter, into
 * {@code build/richtext-corpus/}. The same files are checked into the app at
 * {@code test/fixtures/richtext/}, where a Dart test asserts each one parses and
 * is a fixed point under the app's own node registry. Refresh them with:
 *
 * <pre>
 * ./gradlew test --tests '*RichTextCorpusTest'
 * cp build/richtext-corpus/*.json ../hinata-app/test/fixtures/richtext/
 * </pre>
 *
 * <p>The corpus is every construct hinata actually stores, including the two
 * dialect ones the app has to implement as node types.
 */
class RichTextCorpusTest {

	private static final Map<String, String> CORPUS = corpus();

	/** The corpus itself, shared with the round-trip test in this package. */
	static Map<String, String> corpus() {
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("paragraph", "Ein schlichter Absatz.");
		cases.put("headings", "# Eins\n\n## Zwei\n\n### Drei\n\n###### Sechs");
		cases.put("inline-formats",
				"**fett**, *kursiv*, ~~weg~~, `code` und **fett mit `code` drin**");
		cases.put("links",
				"[die Doku](https://example.org) und [ein *kursiver* Link](https://x.de)");
		cases.put("lists", "- eins\n- zwei\n\n1. erstens\n2. zweitens");
		cases.put("nested-list", "- außen\n  - innen\n    - ganz innen\n- zurück");
		cases.put("task-list", "- [x] erledigt\n- [ ] offen");
		cases.put("quote", "> Ein Zitat mit **Betonung**.");
		cases.put("code-block", "```dart\nvoid main() {\n  print(\"hi\");\n}\n```");
		cases.put("table", "| View | Groups by |\n|---|---|\n| Board | Status |\n| Backlog | Sprint |");
		cases.put("rule", "davor\n\n---\n\ndanach");
		cases.put("image", "![Ringelblumen](https://example.org/f.jpg)");
		cases.put("callout", ":::info\nJede Produktivänderung wird geprüft.\n:::");
		cases.put("callout-with-list", ":::warn\n- eins\n- zwei\n:::");
		// CommonMark reads every `<tag …>` in a paragraph as inline HTML, and in an
		// issue tracker that is content: generic types, widget names, an XML snippet
		// in a bug report. The app has to render it as the literal text it is.
		cases.put("inline-html",
				"Wir brauchen List<String> statt List<Object>.\n\n"
						+ "Das <Widget> rendert nicht, und der <br> fehlt.\n\n"
						+ "XML: <note>Hallo</note> im Body.");
		cases.put("smart-links",
				"Siehe {{issue:HIN-5}}, {{doc:507f1f77bcf86cd799439011}} und "
						+ "{{user:507f191e810c19729de860ea}}.");
		cases.put("mixed",
				"""
						# Release-Checkliste

						Vor jedem Deploy, siehe {{issue:HIN-1}}.

						:::info
						Jede Änderung wird von **{{user:507f191e810c19729de860ea}}** geprüft.
						:::

						- [x] Tests grün
						- [ ] Changelog

						| Umgebung | Ziel |
						|---|---|
						| prod | `track.asta.hn` |

						> Im Zweifel: nicht deployen.
						""");
		return cases;
	}

	private final ObjectMapper mapper = new ObjectMapper();
	private final MarkdownToLexical converter = new MarkdownToLexical(new ObjectMapper());

	@Test
	void writesTheCorpusEveryCaseOfWhichIsANonTrivialDocument() throws IOException {
		Path out = Path.of("build", "richtext-corpus");
		Files.createDirectories(out);

		for (Map.Entry<String, String> entry : CORPUS.entrySet()) {
			String json = mapper.writerWithDefaultPrettyPrinter()
					.writeValueAsString(converter.convert(entry.getValue()));
			Files.writeString(out.resolve(entry.getKey() + ".json"), json + "\n",
					StandardCharsets.UTF_8);

			assertThat(mapper.readTree(json).path("root").path("children"))
					.as("%s produced an empty document", entry.getKey())
					.isNotEmpty();
		}

		// `_`-prefixed files are the corpus's own metadata, not documents; counting
		// them here would make this assertion depend on test ordering.
		assertThat(Files.list(out)
				.filter(p -> p.getFileName().toString().endsWith(".json"))
				.filter(p -> !p.getFileName().toString().startsWith("_"))
				.count())
				.isEqualTo(CORPUS.size());
	}

	/**
	 * Writes the markdown the corpus was produced from, next to the documents.
	 *
	 * <p>The app converts markdown too — the comment composer previews a draft,
	 * and a row the migration skipped is converted on read. That conversion has to
	 * land on the same document this one does, or the preview promises something
	 * the save does not keep.
	 *
	 * <p>Shipping the inputs rather than transcribing them into the Dart test is
	 * the point: a transcribed string drifts silently from the one that produced
	 * the fixture, and then the comparison passes while comparing the wrong
	 * things. A case added here becomes an obligation on the app for free.
	 */
	@Test
	void writesTheMarkdownEachDocumentWasProducedFrom() throws IOException {
		Path out = Path.of("build", "richtext-corpus");
		Files.createDirectories(out);
		Files.writeString(out.resolve("_sources.json"),
				mapper.writerWithDefaultPrettyPrinter().writeValueAsString(CORPUS) + "\n",
				StandardCharsets.UTF_8);

		assertThat(mapper.readTree(out.resolve("_sources.json").toFile()).size())
				.isEqualTo(CORPUS.size());
	}

	@Test
	void everyCorpusDocumentSurvivesAReadBack() {
		// Cheap self-check: what the converter emits must at least be readable by
		// the server's own reader, bounds and all, before the app is asked to open it.
		for (Map.Entry<String, String> entry : CORPUS.entrySet()) {
			String json = new RichTextService().fromMarkdown(entry.getValue()).doc();
			assertThat(json).as(entry.getKey()).isNotNull();
			assertThat(LexicalJson.parse(mapper, json).path("root").path("children"))
					.as(entry.getKey())
					.isNotEmpty();
		}
	}

	@Test
	void everyCorpusDocumentProjectsToReadableText() {
		for (Map.Entry<String, String> entry : CORPUS.entrySet()) {
			String text = new RichTextService().fromMarkdown(entry.getValue()).text();
			assertThat(text).as("%s lost all its text", entry.getKey()).isNotBlank();
			assertThat(text)
					.as("%s leaked markup into the search index", entry.getKey())
					.doesNotContain("{{").doesNotContain(":::").doesNotContain("```");
		}
	}
}
