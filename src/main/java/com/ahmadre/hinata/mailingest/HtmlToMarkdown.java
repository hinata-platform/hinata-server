package com.ahmadre.hinata.mailingest;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Converts an HTML e-mail body into clean Markdown so it renders correctly in an
 * issue's (Markdown) description instead of dumping raw markup or leftover CSS.
 *
 * <p>HTML mails — especially newsletters and transactional templates — carry large
 * inline {@code <style>} blocks, tracking pixels and {@code <head>} metadata. A naive
 * tag strip ({@code replaceAll("<[^>]+>", " ")}) leaves the stylesheet <em>text</em>
 * behind, which is exactly the noise we want gone. Here jsoup removes those nodes and
 * decodes entities, then flexmark turns the remaining structure (headings, links,
 * lists, tables, emphasis) into Markdown.
 */
final class HtmlToMarkdown {

	private static final FlexmarkHtmlConverter CONVERTER = FlexmarkHtmlConverter.builder().build();

	private HtmlToMarkdown() {
	}

	/**
	 * @param html a raw HTML mail body (may be {@code null})
	 * @return the body as Markdown; never {@code null}. Falls back to plain text
	 *         extraction if structured conversion fails, and to the input if even
	 *         parsing fails, so ticket creation is never aborted by a broken body.
	 */
	static String convert(String html) {
		if (html == null || html.isBlank()) {
			return "";
		}
		try {
			Document doc = Jsoup.parse(html);
			// Drop non-content nodes whose text would otherwise leak into the body.
			doc.select("style, script, head, title, meta, link, noscript").remove();
			flattenLayoutTables(doc);
			String source = doc.body() != null ? doc.body().outerHtml() : doc.outerHtml();
			String markdown = CONVERTER.convert(source);
			return tidy(markdown);
		}
		catch (Exception ex) {
			try {
				// Structured conversion failed: at least jsoup's text() ignores
				// <style>/<script> content and decodes entities.
				return Jsoup.parse(html).text().strip();
			}
			catch (Exception inner) {
				return html;
			}
		}
	}

	/**
	 * HTML mails — newsletters and transactional templates especially — build their
	 * layout out of nested {@code <table>}s used purely for spacing. Converting those
	 * to Markdown tables yields walls of {@code | --- | --- |} delimiter rows that
	 * render as endless horizontal lines. We unwrap the table scaffolding and turn
	 * each cell into a block ({@code div}) so the content becomes linear, readable
	 * text. Genuine data tables are rare in support mail and degrade gracefully to
	 * stacked lines rather than a dash wall.
	 */
	private static void flattenLayoutTables(Document doc) {
		doc.select("td, th, caption").forEach(cell -> cell.tagName("div"));
		doc.select("table, thead, tbody, tfoot, tr, colgroup, col").forEach(el -> el.unwrap());
	}

	/**
	 * Drops leftover rule/table-delimiter lines (composed only of {@code - | :} and
	 * spaces) and collapses the blank-line runs a div-heavy template produces.
	 */
	private static String tidy(String markdown) {
		StringBuilder out = new StringBuilder();
		for (String line : markdown.split("\n", -1)) {
			if (!isRuleNoise(line)) {
				out.append(line).append('\n');
			}
		}
		return out.toString().replaceAll("\n{3,}", "\n\n").strip();
	}

	/** A line of only {@code - | :} and spaces (>=3 chars) is layout noise, not content. */
	private static boolean isRuleNoise(String line) {
		String trimmed = line.strip();
		return trimmed.length() >= 3
				&& trimmed.chars().allMatch(c -> c == '-' || c == '|' || c == ':' || c == ' ');
	}
}
