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
			String source = doc.body() != null ? doc.body().outerHtml() : doc.outerHtml();
			String markdown = CONVERTER.convert(source);
			// Collapse the runs of blank lines flexmark emits for table/div-heavy mail.
			return markdown.replaceAll("\n{3,}", "\n\n").strip();
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
}
