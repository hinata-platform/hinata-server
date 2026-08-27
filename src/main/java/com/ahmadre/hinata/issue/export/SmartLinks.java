package com.ahmadre.hinata.issue.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the {@code {{user:…}}} / {@code {{issue:…}}} / {@code {{doc:…}}} tokens
 * an exported description carries into the names they stand for.
 *
 * <p>The tokens are how a smart link survives the trip through markdown.
 * {@code LexicalToMarkdown} writes them because its contract is a fixed point —
 * markdown that becomes a document that becomes the same markdown again — and an
 * agent reading a description over MCP needs the id back in order to write the
 * link back. That is right for an agent and wrong for a person: an exported
 * ticket read
 *
 * <pre>{@code {{user:6a3d0d9774fd69a89d133e9c}} has all the accesses already}</pre>
 *
 * <p>which names nobody. So the tokens are resolved here, in the export, and
 * {@code LexicalToMarkdown} is left exactly as it is.
 *
 * <h2>Why the labels come out of the document</h2>
 *
 * <p>A smart link stores a {@code label} beside its target — the target's title
 * as it read when the link was made — and that label is the safe thing to print.
 * It is already part of the description the caller is reading, so printing it
 * discloses nothing they were not already shown; looking the target up instead
 * would make an export answer "what is the title of the article with this id",
 * for any id, to anyone who can edit the ticket. {@code IssueLinkService} refuses
 * to be that oracle for links and {@code IssueExportService#dependsOn} refuses it
 * for dependencies; this is the same rule in the same document.
 *
 * <p>People are the exception, and deliberately so. A mention is resolved live
 * against the batch of names the export has already read, because a display name
 * is the one label that is worth being current — somebody who changed their name
 * two years ago should not be addressed by the old one — and because those names
 * are printed in the export's own fields regardless.
 *
 * <p>An issue needs neither: its token already carries the readable id, which is
 * what a reader would look for anyway.
 */
final class SmartLinks {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * A token as {@code LexicalToMarkdown} writes it.
	 *
	 * <p>The id is bounded and may not contain a brace or whitespace, so a
	 * description full of literal braces cannot make this scan quadratic and a
	 * half-written token stays literal text rather than swallowing the paragraph
	 * after it.
	 */
	private static final Pattern TOKEN =
			Pattern.compile("\\{\\{(user|issue|doc):([^{}\\s]{1,128})}}");

	/** Nodes to walk when harvesting labels — the same ceiling the parser uses. */
	private static final int MAX_NODES = 20_000;

	private SmartLinks() {
	}

	/**
	 * The label each smart link in [storedJson] was written with, keyed
	 * {@code kind:targetId}.
	 *
	 * <p>Read straight off the stored document rather than through
	 * {@code LexicalToMarkdown}, because the markdown is exactly where the label
	 * is lost: the token has room for a kind and an id and nothing else.
	 *
	 * <p>A document that cannot be parsed yields no labels, which is the same
	 * outcome as a document with none — the caller then prints the readable id
	 * for an issue and a generic word for an article, and nothing fails.
	 */
	static Map<String, String> labels(String storedJson) {
		if (storedJson == null || storedJson.isBlank()) {
			return Map.of();
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(storedJson);
		}
		catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			return Map.of();
		}
		Map<String, String> found = new HashMap<>();
		// Iterative rather than recursive: this walks a stored document, which is
		// untrusted input, and a depth bound is one more thing to keep in step
		// with the parser's.
		Deque<JsonNode> pending = new ArrayDeque<>();
		pending.push(root);
		int seen = 0;
		while (!pending.isEmpty() && seen++ < MAX_NODES) {
			JsonNode node = pending.pop();
			if (node.isObject() && "smartlink".equals(node.path("type").asText(""))) {
				String label = node.path("label").asText("");
				if (!label.isBlank()) {
					found.putIfAbsent(key(node.path("kind").asText(""),
							node.path("targetId").asText("")), label);
				}
			}
			for (JsonNode child : node) {
				if (child.isObject() || child.isArray()) {
					pending.push(child);
				}
			}
		}
		return found;
	}

	static String key(String kind, String targetId) {
		return kind + ":" + targetId;
	}

	/**
	 * Adds every user id mentioned in [blocks] to [into], up to [cap] entries in
	 * total.
	 *
	 * <p>Read off the parsed blocks rather than the stored JSON, because a mention
	 * does not need a document to exist in: descriptions written before the editor
	 * arrived are plain text and carry the same tokens literally, and the seeded
	 * demo data is exactly that shape. The blocks are what will be rendered, so
	 * they are the honest place to ask what is in them.
	 *
	 * <p>[cap] counts the whole set and not this call's contribution, so a
	 * description and five hundred comments share one ceiling rather than each
	 * getting their own.
	 */
	static void userIds(List<ExportBlock> blocks, java.util.Set<String> into, int cap) {
		for (ExportBlock block : blocks) {
			if (into.size() >= cap) {
				return;
			}
			switch (block) {
				case ExportBlock.Heading heading -> scan(heading.spans(), into, cap);
				case ExportBlock.Paragraph paragraph -> scan(paragraph.spans(), into, cap);
				case ExportBlock.Quote quote -> scan(quote.spans(), into, cap);
				case ExportBlock.BulletList list -> {
					for (List<ExportBlock.Span> item : list.items()) {
						scan(item, into, cap);
					}
				}
				case ExportBlock.Table table -> {
					for (List<String> row : table.rows()) {
						for (String cell : row) {
							scan(cell, into, cap);
						}
					}
					for (String header : table.headers()) {
						scan(header, into, cap);
					}
				}
				// Verbatim by definition, and skipped by resolve() for the same
				// reason — so a name looked up for one would never be printed.
				case ExportBlock.Code ignored -> {
				}
				case ExportBlock.Rule ignored -> {
				}
			}
		}
	}

	private static void scan(List<ExportBlock.Span> spans, java.util.Set<String> into, int cap) {
		for (ExportBlock.Span span : spans) {
			scan(span.text(), into, cap);
		}
	}

	private static void scan(String value, java.util.Set<String> into, int cap) {
		if (value == null || value.indexOf('{') < 0 || into.size() >= cap) {
			return;
		}
		Matcher matcher = TOKEN.matcher(value);
		while (matcher.find() && into.size() < cap) {
			if ("user".equals(matcher.group(1))) {
				into.add(matcher.group(2));
			}
		}
	}

	/**
	 * [blocks] with every token replaced by what [naming] makes of it.
	 *
	 * <p>[naming] is given the kind and the target id and answers with the text to
	 * print. It is never asked to produce markup: the blocks are already parsed,
	 * so a name containing an asterisk or a pipe is text and stays text. Resolving
	 * before the parse would have made it syntax.
	 *
	 * <p>Code blocks are left alone. A fenced block is quoted verbatim by
	 * definition, and a token inside one is far more likely to be somebody
	 * documenting the format than a link they meant to make.
	 */
	static List<ExportBlock> resolve(List<ExportBlock> blocks,
			BiFunction<String, String, String> naming) {
		if (blocks.isEmpty()) {
			return blocks;
		}
		List<ExportBlock> out = new ArrayList<>(blocks.size());
		for (ExportBlock block : blocks) {
			out.add(switch (block) {
				case ExportBlock.Heading heading ->
						new ExportBlock.Heading(heading.level(), spans(heading.spans(), naming));
				case ExportBlock.Paragraph paragraph ->
						new ExportBlock.Paragraph(spans(paragraph.spans(), naming));
				case ExportBlock.Quote quote ->
						new ExportBlock.Quote(spans(quote.spans(), naming));
				case ExportBlock.BulletList list -> new ExportBlock.BulletList(list.ordered(),
						list.items().stream().map(item -> spans(item, naming)).toList());
				case ExportBlock.Table table -> new ExportBlock.Table(
						table.headers().stream().map(cell -> text(cell, naming)).toList(),
						table.rows().stream()
								.map(row -> row.stream().map(cell -> text(cell, naming)).toList())
								.toList());
				case ExportBlock.Code code -> code;
				case ExportBlock.Rule rule -> rule;
			});
		}
		return out;
	}

	private static List<ExportBlock.Span> spans(List<ExportBlock.Span> spans,
			BiFunction<String, String, String> naming) {
		List<ExportBlock.Span> out = new ArrayList<>(spans.size());
		for (ExportBlock.Span span : spans) {
			String replaced = text(span.text(), naming);
			out.add(replaced.equals(span.text()) ? span
					: new ExportBlock.Span(replaced, span.bold(), span.italic(),
							span.code(), span.strike()));
		}
		return out;
	}

	/** [value] with its tokens named. Returns [value] itself when it has none. */
	static String text(String value, BiFunction<String, String, String> naming) {
		if (value == null || value.isEmpty() || value.indexOf('{') < 0) {
			return value == null ? "" : value;
		}
		Matcher matcher = TOKEN.matcher(value);
		if (!matcher.find()) {
			return value;
		}
		StringBuilder out = new StringBuilder(value.length());
		int from = 0;
		do {
			out.append(value, from, matcher.start());
			String named = naming.apply(matcher.group(1), matcher.group(2));
			// A resolver that has nothing to say leaves the token alone rather than
			// deleting the reference: a sentence missing its subject is worse than
			// one carrying an id.
			out.append(named == null || named.isBlank() ? matcher.group() : named);
			from = matcher.end();
		}
		while (matcher.find());
		out.append(value, from, value.length());
		return out.toString();
	}
}
