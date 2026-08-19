package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.richtext.LexicalToMarkdown;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The description's journey: the Lexical document the database holds, out
 * through the markdown renderer the rest of the platform already uses, into the
 * blocks the four export formats lay out.
 *
 * <p>Going the whole way rather than starting from markdown is the point. The
 * acceptance criterion is that a formatted description <em>survives the trip out
 * of Lexical</em>, and a test that begins with a markdown string would pass
 * whether or not the document half works.
 *
 * <p>What a description is not allowed to cost, on the other hand, is asked in
 * {@code ExportHardeningTest} — every ceiling in one place, rather than the
 * table half there and the code-block half here.
 */
class MarkdownBlocksTest {

	private final RichTextService richText = new RichTextService();

	/** Markdown → the stored document → markdown again → blocks, as an export does. */
	private List<ExportBlock> throughStorage(String markdown) {
		RichText stored = richText.fromMarkdown(markdown);
		return MarkdownBlocks.of(LexicalToMarkdown.fromStored(stored.doc(), stored.text()));
	}

	@Test
	void headingsKeepTheirLevel() {
		List<ExportBlock> blocks = throughStorage("# One\n\n## Two\n\n### Three");

		assertThat(blocks).hasSize(3);
		assertThat(blocks).allSatisfy(block -> assertThat(block).isInstanceOf(ExportBlock.Heading.class));
		assertThat(blocks).extracting(block -> ((ExportBlock.Heading) block).level())
				.containsExactly(1, 2, 3);
		assertThat(text(blocks.get(1))).isEqualTo("Two");
	}

	@Test
	void bothKindsOfListSurviveWithTheirItems() {
		List<ExportBlock> blocks = throughStorage("- first\n- second\n\n1. one\n2. two");

		assertThat(blocks).hasSize(2);
		ExportBlock.BulletList bullets = (ExportBlock.BulletList) blocks.get(0);
		ExportBlock.BulletList numbers = (ExportBlock.BulletList) blocks.get(1);
		assertThat(bullets.ordered()).isFalse();
		assertThat(bullets.items()).extracting(ExportBlock.Span::plain)
				.containsExactly("first", "second");
		assertThat(numbers.ordered()).isTrue();
		assertThat(numbers.items()).extracting(ExportBlock.Span::plain)
				.containsExactly("one", "two");
	}

	@Test
	void aFencedBlockKeepsItsLanguageAndItsLineBreaks() {
		List<ExportBlock> blocks = throughStorage("```dart\nvoid main() {\n  print(1);\n}\n```");

		assertThat(blocks).singleElement().isInstanceOf(ExportBlock.Code.class);
		ExportBlock.Code code = (ExportBlock.Code) blocks.get(0);
		assertThat(code.language()).isEqualTo("dart");
		assertThat(code.text()).contains("void main() {").contains("  print(1);");
	}

	@Test
	void aTableKeepsItsHeaderAndItsRows() {
		List<ExportBlock> blocks = throughStorage(
				"| Format | Library |\n| --- | --- |\n| docx | POI |\n| pdf | openpdf |");

		assertThat(blocks).singleElement().isInstanceOf(ExportBlock.Table.class);
		ExportBlock.Table table = (ExportBlock.Table) blocks.get(0);
		assertThat(table.headers()).containsExactly("Format", "Library");
		assertThat(table.rows()).containsExactly(
				List.of("docx", "POI"), List.of("pdf", "openpdf"));
	}

	@Test
	void emphasisSurvivesAsSpansRatherThanAsSyntax() {
		List<ExportBlock> blocks = throughStorage("plain **bold** *italic* `code` ~~gone~~");

		ExportBlock.Paragraph paragraph = (ExportBlock.Paragraph) blocks.get(0);
		assertThat(ExportBlock.Span.plain(paragraph.spans()))
				.as("no markdown syntax leaks into the text")
				.doesNotContain("*").doesNotContain("`").doesNotContain("~");
		assertThat(paragraph.spans()).anySatisfy(span -> {
			assertThat(span.text()).isEqualTo("bold");
			assertThat(span.bold()).isTrue();
		});
		assertThat(paragraph.spans()).anySatisfy(span -> {
			assertThat(span.text()).isEqualTo("italic");
			assertThat(span.italic()).isTrue();
		});
		assertThat(paragraph.spans()).anySatisfy(span -> {
			assertThat(span.text()).isEqualTo("code");
			assertThat(span.code()).isTrue();
		});
		assertThat(paragraph.spans()).anySatisfy(span -> {
			assertThat(span.text()).isEqualTo("gone");
			assertThat(span.strike()).isTrue();
		});
	}

	@Test
	void aQuoteAndARuleKeepTheirKind() {
		List<ExportBlock> blocks = throughStorage("> quoted\n\n---");

		assertThat(blocks).hasAtLeastOneElementOfType(ExportBlock.Quote.class);
		assertThat(blocks).hasAtLeastOneElementOfType(ExportBlock.Rule.class);
	}

	@Test
	void anEmptyDescriptionContributesNothing() {
		assertThat(MarkdownBlocks.of(null)).isEmpty();
		assertThat(MarkdownBlocks.of("   ")).isEmpty();
		assertThat(throughStorage("")).isEmpty();
	}

	private static String text(ExportBlock block) {
		return switch (block) {
			case ExportBlock.Heading heading -> ExportBlock.Span.plain(heading.spans());
			case ExportBlock.Paragraph paragraph -> ExportBlock.Span.plain(paragraph.spans());
			case ExportBlock.Quote quote -> ExportBlock.Span.plain(quote.spans());
			case ExportBlock.Code code -> code.text();
			default -> "";
		};
	}
}
