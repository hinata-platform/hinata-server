package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
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
	 * The surface assumed when a caller does not name one.
	 *
	 * <p>Long-form technical prose is the only honest default here: every call
	 * that omits a surface is writing a <em>body</em> — a description, an article,
	 * a converted mail — and judging one of those as if it were a display name
	 * would refuse stack traces and cost someone their written defect report. A
	 * caller writing something shorter, or something authored outside the
	 * organisation, says so through the {@link ModerationSurface} overloads, and
	 * every ingress where that actually changes the verdict does.
	 */
	private static final ModerationSurface DEFAULT_SURFACE = ModerationSurface.ISSUE_DESCRIPTION;

	/**
	 * The gate, or {@code null} on an instance built outside the container.
	 *
	 * @see #RichTextService()
	 */
	private final ModerationService moderation;

	@Autowired
	public RichTextService(ModerationService moderation) {
		this.moderation = moderation;
	}

	/**
	 * A converter with <b>no gate</b>, for callers that convert documents they
	 * never store — the wire-format conformance corpus and the markdown
	 * round-trip, which compare documents and throw them away.
	 *
	 * <p>The constructor above carries {@code @Autowired} precisely because this
	 * one exists: with two constructors and no annotation Spring would pick the
	 * no-arg one and the product would run with moderation silently switched off,
	 * which is the single worst way this class could fail.
	 */
	public RichTextService() {
		this(null);
	}

	/**
	 * Accepts a Lexical document from a client: validates it against the bounds in
	 * {@link LexicalJson}, derives its plain text, and puts that text past the
	 * moderation gate as {@link #DEFAULT_SURFACE}.
	 *
	 * @throws com.ahmadre.hinata.common.ApiException 400 when the document is
	 *         unreadable or exceeds the structural limits
	 * @throws com.ahmadre.hinata.moderation.ModerationException 422 when the
	 *         content is refused
	 */
	public RichText fromLexical(String json) {
		return fromLexical(json, DEFAULT_SURFACE);
	}

	/** As {@link #fromLexical(String)}, judged as content written on [surface]. */
	public RichText fromLexical(String json, ModerationSurface surface) {
		if (json == null || json.isBlank()) return RichText.EMPTY;
		JsonNode document = LexicalJson.parse(MAPPER, json);
		if (LexicalJson.isBlank(document)) return RichText.EMPTY;
		String text = LexicalJson.plainText(document);
		gate(text, surface);
		return new RichText(json, text, issueKeys(document));
	}

	/**
	 * Converts markdown to a Lexical document and derives its plain text, bounded
	 * on both ends: the source may not exceed {@link #MAX_MARKDOWN_CHARS} and the
	 * converted document may not exceed {@link LexicalJson#MAX_JSON_CHARS}.
	 *
	 * @throws com.ahmadre.hinata.common.ApiException 400 {@code error.richtext.tooLarge}
	 * @throws com.ahmadre.hinata.moderation.ModerationException 422 when the
	 *         content is refused
	 */
	public RichText fromMarkdown(String source) {
		return fromMarkdown(source, DEFAULT_SURFACE);
	}

	/** As {@link #fromMarkdown(String)}, judged as content written on [surface]. */
	public RichText fromMarkdown(String source, ModerationSurface surface) {
		return convert(source, MAX_MARKDOWN_CHARS, surface);
	}

	/**
	 * As {@link #fromMarkdown}, but for markdown that is <em>already stored</em>
	 * and therefore predates the input bound — the one-time migration of legacy
	 * bodies. Refusing those on input would leave them unconverted forever; the
	 * output bound still applies, so a body that cannot become a readable document
	 * is reported rather than written.
	 *
	 * <p>Not moderated, for the same reason the input bound is relaxed: this is a
	 * re-derivation of content the database already holds, not a write. Blocking
	 * it would strand the body in a format nothing can read while leaving the
	 * offending text exactly where it already is. Stored content reaches a
	 * moderator through the queue, never by refusing a migration.
	 */
	public RichText fromStoredMarkdown(String source) {
		return convert(source, LexicalJson.MAX_JSON_CHARS, null);
	}

	private RichText convert(String source, int maxSourceChars, ModerationSurface surface) {
		if (source == null || source.isBlank()) return RichText.EMPTY;
		if (source.length() > maxSourceChars) throw tooLarge();
		ObjectNode document = MARKDOWN.convert(source);
		if (LexicalJson.isBlank(document)) return RichText.EMPTY;
		String json = write(document);
		// The read side refuses a document past this bound. Storing one anyway
		// produces content that loads and can never be saved again.
		if (json.length() > LexicalJson.MAX_JSON_CHARS) throw tooLarge();
		String text = LexicalJson.plainText(document);
		gate(text, surface);
		return new RichText(json, text, issueKeys(document));
	}

	/**
	 * Puts one body past the moderation gate.
	 *
	 * <p>What is judged is the <em>derived plain text</em>, never the document.
	 * Scoring the JSON would score node type names and inline styling, and — far
	 * worse — an author could split a word across two text nodes and have the
	 * filter read two harmless fragments while every reader sees the word. The
	 * flattening is what a person actually reads, so it is what has to be judged.
	 *
	 * <p>A {@code null} surface means the content is already stored and is only
	 * being re-derived; a {@code null} service means this instance was built
	 * outside the container. Both are documented above and nowhere else.
	 */
	private void gate(String text, ModerationSurface surface) {
		if (moderation == null || surface == null) return;
		moderation.checkText(text, surface);
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
		return fromRequest(doc, markdown, storedDoc, storedText, DEFAULT_SURFACE);
	}

	/**
	 * As {@link #fromRequest(String, String, String, String)}, judged as content
	 * written on [surface]. The "no change" branch is decided <em>before</em> the
	 * gate on purpose: a save that carries no edit must not start failing because
	 * a body written under an older ruleset would not pass today's.
	 */
	public RichText fromRequest(String doc, String markdown, String storedDoc, String storedText,
			ModerationSurface surface) {
		if (doc != null) return fromLexical(doc, surface);
		if (markdown == null) return null;
		if (storedDoc != null && !storedDoc.isBlank()
				&& markdown.strip().equals(storedText == null ? "" : storedText.strip())) {
			return null;
		}
		return fromMarkdown(markdown, surface);
	}

	/** As {@link #fromRequest(String, String, String, String)}, for a new entity. */
	public RichText fromRequest(String doc, String markdown) {
		return fromRequest(doc, markdown, null, null);
	}

	/** As {@link #fromRequest(String, String)}, judged as content on [surface]. */
	public RichText fromRequest(String doc, String markdown, ModerationSurface surface) {
		return fromRequest(doc, markdown, null, null, surface);
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
