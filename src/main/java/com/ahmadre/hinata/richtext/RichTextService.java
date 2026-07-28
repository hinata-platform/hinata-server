package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
	 * Largest markdown accepted from a live caller — a request, an MCP tool, an
	 * inbound mail, a smart commit.
	 *
	 * <p>It exists because the two directions have to agree: {@link #fromLexical}
	 * refuses a document over {@link LexicalJson#MAX_JSON_CHARS}, so accepting
	 * markdown that <em>expands</em> past that bound would store content the server
	 * then refuses to read — readable, and permanently uneditable. Measured
	 * worst-case expansion for dense inline formatting is ~29×, so this bound keeps
	 * the converted document inside the read bound with room to spare. It is also
	 * the only length guard MCP tool parameters get: {@code @McpToolParam} is not
	 * bean-validated, so putting the check here covers MCP, e-mail, git and the
	 * seeder at once.
	 */
	public static final int MAX_MARKDOWN_CHARS = 30_000;

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

	/**
	 * Converts markdown to a Lexical document and derives its plain text, bounded
	 * on both ends: the source may not exceed {@link #MAX_MARKDOWN_CHARS} and the
	 * converted document may not exceed {@link LexicalJson#MAX_JSON_CHARS}.
	 *
	 * @throws com.ahmadre.hinata.common.ApiException 400 {@code error.richtext.tooLarge}
	 */
	public RichText fromMarkdown(String source) {
		return convert(source, MAX_MARKDOWN_CHARS);
	}

	/**
	 * As {@link #fromMarkdown}, but for markdown that is <em>already stored</em>
	 * and therefore predates the input bound — the one-time migration of legacy
	 * bodies. Refusing those on input would leave them unconverted forever; the
	 * output bound still applies, so a body that cannot become a readable document
	 * is reported rather than written.
	 */
	public RichText fromStoredMarkdown(String source) {
		return convert(source, LexicalJson.MAX_JSON_CHARS);
	}

	private RichText convert(String source, int maxSourceChars) {
		if (source == null || source.isBlank()) return RichText.EMPTY;
		if (source.length() > maxSourceChars) throw tooLarge();
		ObjectNode document = MARKDOWN.convert(source);
		if (LexicalJson.isBlank(document)) return RichText.EMPTY;
		String json = write(document);
		// The read side refuses a document past this bound. Storing one anyway
		// produces content that loads and can never be saved again.
		if (json.length() > LexicalJson.MAX_JSON_CHARS) throw tooLarge();
		return new RichText(json, LexicalJson.plainText(document), issueKeys(document));
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
	 * <p>{@code storedDoc} / {@code storedText} are the entity's current values and
	 * are what stop that compatibility from becoming data loss. An app build older
	 * than this change does GET → edit → PUT and sends only the legacy field — which
	 * now holds <em>plain text</em>, not markdown. Converting that unconditionally
	 * would replace a formatted document with a flattening of itself on every
	 * unrelated save. So a legacy field equal to the stored derived text is "no
	 * change" and leaves the document alone; only a field that genuinely differs is
	 * a real edit and is converted.
	 *
	 * @return {@code null} when neither field is present, or when the legacy field
	 *         carries no change — both of which are the PATCH convention for
	 *         "leave it as it is"
	 */
	public RichText fromRequest(String doc, String markdown, String storedDoc, String storedText) {
		if (doc != null) return fromLexical(doc);
		if (markdown == null) return null;
		if (storedDoc != null && !storedDoc.isBlank()
				&& markdown.strip().equals(storedText == null ? "" : storedText.strip())) {
			return null;
		}
		return fromMarkdown(markdown);
	}

	/** As {@link #fromRequest(String, String, String, String)}, for a new entity. */
	public RichText fromRequest(String doc, String markdown) {
		return fromRequest(doc, markdown, null, null);
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

	/** The shape of a readable issue id, and the only thing stored as a backlink. */
	private static final Pattern ISSUE_KEY = Pattern.compile("[A-Za-z]+-\\d+");

	/**
	 * Cap on stored backlinks per document. The list is a multikey index, so its
	 * length is index growth driven by request content; one hostile document
	 * produced 8 000 entries. Real documents link to a handful of issues.
	 */
	private static final int MAX_ISSUE_KEYS = 200;

	/**
	 * Readable issue ids the document links to, upper-cased for a case-insensitive
	 * match. Derived in the same pass as the plain text so a stored backlink list
	 * can never disagree with the document it came from.
	 *
	 * <p>A {@code targetId} is whatever a client put in a node, so only values that
	 * are actually issue keys are kept: the rest cannot match a backlink query and
	 * would only be junk in an index.
	 */
	private static List<String> issueKeys(JsonNode document) {
		return LexicalJson.smartLinkTargets(document, "issue").stream()
				.filter(id -> ISSUE_KEY.matcher(id).matches())
				.map(id -> id.toUpperCase(Locale.ROOT))
				.distinct()
				.limit(MAX_ISSUE_KEYS)
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

	private static ApiException tooLarge() {
		return ApiException.badRequest("error.richtext.tooLarge");
	}
}
