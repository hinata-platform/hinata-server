package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * The single writer of rich-text content.
 *
 * <p>Every path that stores a description, an article or a comment goes through
 * here, which is what makes {@link RichText#text()} trustworthy: it is always the
 * projection of the document that sits next to it, never something a client sent.
 * A client can lie about the plain text; it cannot lie about a value it does not
 * supply.
 *
 * <p>There are exactly two ways content enters the system, and both end in the
 * same representation:
 *
 * <ul>
 *   <li>{@link #fromLexical(String)} — the app, which authors Lexical directly;</li>
 *   <li>{@link #fromMarkdown(String)} — MCP agents and inbound e-mail, converted
 *       on the way in so the database never holds two formats.</li>
 * </ul>
 */
@Service
public class RichTextService {

	/**
	 * A mapper this package owns, deliberately not the application's.
	 *
	 * <p>Two reasons, and the first one is not about Spring Boot 4 shipping a
	 * Jackson 3 bean while this code reads Jackson 2 trees:
	 *
	 * <ul>
	 *   <li>The Lexical wire format is a contract with the editor, not a
	 *       presentation choice. If someone later sets
	 *       {@code spring.jackson.default-property-inclusion=non_null} for the HTTP
	 *       layer, an injected mapper would silently stop emitting
	 *       {@code "direction": null} — and an absent key is not equal to a null
	 *       one, so documents would quietly stop matching. A stored format must not
	 *       move when an unrelated HTTP setting does.</li>
	 *   <li>A default mapper is exactly what the conformance corpus was generated
	 *       against, so the format stays comparable to it.</li>
	 * </ul>
	 *
	 * <p>{@link ObjectMapper} is thread-safe once configured, and this one never is
	 * reconfigured.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final MarkdownToLexical MARKDOWN = new MarkdownToLexical(MAPPER);

	/**
	 * Accepts a Lexical document from a client: validates it against the bounds in
	 * {@link LexicalJson} and derives its plain text.
	 *
	 * @throws com.ahmadre.hinata.common.ApiException 400 when the document is
	 *         unreadable or exceeds the structural limits
	 */
	public RichText fromLexical(String json) {
		if (json == null || json.isBlank()) return RichText.EMPTY;
		JsonNode document = LexicalJson.parse(MAPPER, json);
		if (LexicalJson.isBlank(document)) return RichText.EMPTY;
		return new RichText(json, LexicalJson.plainText(document), issueKeys(document));
	}

	/** Converts markdown to a Lexical document and derives its plain text. */
	public RichText fromMarkdown(String source) {
		if (source == null || source.isBlank()) return RichText.EMPTY;
		ObjectNode document = MARKDOWN.convert(source);
		if (LexicalJson.isBlank(document)) return RichText.EMPTY;
		return new RichText(write(document), LexicalJson.plainText(document), issueKeys(document));
	}

	/**
	 * Content from a request that may carry either form: a Lexical document, or
	 * markdown to be converted. The document wins when both are present.
	 *
	 * <p>Accepting markdown here is not a compatibility shim, it is the same rule
	 * that governs MCP and e-mail — markdown is an input format and never a
	 * storage format. It also means an app that has not been updated yet keeps
	 * working instead of silently writing nothing.
	 *
	 * @return {@code null} when neither field is present, which is the PATCH
	 *         convention for "no change"
	 */
	public RichText fromRequest(String doc, String markdown) {
		if (doc != null) return fromLexical(doc);
		if (markdown != null) return fromMarkdown(markdown);
		return null;
	}

	/** Plain text of a stored document, for consumers that only want words. */
	public String plainText(String json) {
		if (json == null || json.isBlank()) return "";
		try {
			return LexicalJson.plainText(MAPPER.readTree(json));
		} catch (JsonProcessingException e) {
			return "";
		}
	}

	/**
	 * Plain text of a stored document with per-node overrides — how the
	 * notification layer renders a user smart link as {@code @DisplayName} rather
	 * than dropping an opaque id into a push body.
	 */
	public String plainText(String json, LexicalJson.NodeText override) {
		if (json == null || json.isBlank()) return "";
		try {
			return LexicalJson.plainText(MAPPER.readTree(json), override);
		} catch (JsonProcessingException e) {
			return "";
		}
	}

	/**
	 * Ids of the users a document mentions. Replaces scanning the raw text for
	 * {@code {{user:<id>}}} tokens now that a mention is a node.
	 */
	public Set<String> mentionedUsers(String json) {
		if (json == null || json.isBlank()) return Set.of();
		try {
			return LexicalJson.smartLinkTargets(MAPPER.readTree(json), "user");
		} catch (JsonProcessingException e) {
			return Set.of();
		}
	}

	/** Reads a stored document, or {@code null} when it is absent or unreadable. */
	public JsonNode read(String json) {
		if (json == null || json.isBlank()) return null;
		try {
			return MAPPER.readTree(json);
		} catch (JsonProcessingException e) {
			return null;
		}
	}

	/**
	 * Readable issue ids the document links to, upper-cased for a case-insensitive
	 * match. Derived in the same pass as the plain text so a stored backlink list
	 * can never disagree with the document it came from.
	 */
	private static List<String> issueKeys(JsonNode document) {
		return LexicalJson.smartLinkTargets(document, "issue").stream()
				.map(id -> id.toUpperCase(java.util.Locale.ROOT))
				.distinct()
				.toList();
	}

	private String write(ObjectNode document) {
		try {
			return MAPPER.writeValueAsString(document);
		} catch (JsonProcessingException e) {
			// The tree was built here from primitives; it cannot fail to serialize.
			throw new IllegalStateException("failed to serialize a Lexical document", e);
		}
	}
}
