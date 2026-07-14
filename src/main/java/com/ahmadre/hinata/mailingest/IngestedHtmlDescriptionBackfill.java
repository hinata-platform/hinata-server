package com.ahmadre.hinata.mailingest;

import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One-time, idempotent backfill for e-mail-ingested issues created before HTML mail
 * bodies were converted to Markdown. Those descriptions still hold raw HTML dumped
 * straight into the (Markdown) description; this re-runs their body through
 * {@link HtmlToMarkdown}.
 *
 * <p>Only ingested issues whose description body still contains HTML markup are
 * touched. Once converted, a document no longer matches, so running on every boot is
 * safe. The "Created from e-mail by ..." header is preserved verbatim — only the part
 * after the {@code ---} separator (the actual mail body) is converted.
 *
 * <p>Note: the older <em>multipart</em> fallback bug leaked stylesheet <em>text</em>
 * (tags already stripped) rather than markup, so those bodies carry no detectable HTML
 * and are intentionally left untouched — there is no reliable, non-destructive way to
 * tell that noise apart from a legitimately authored plain-text description.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class IngestedHtmlDescriptionBackfill implements ApplicationRunner {

	/** Separator between the "Created from e-mail by ..." header and the mail body. */
	private static final String BODY_SEPARATOR = "\n\n---\n\n";

	/** Matches the mail-body truncation limit used at ingest ({@code EmailIngestService}). */
	private static final int MAX_BODY = 20000;

	/**
	 * Signals genuine HTML markup — an opening/closing block or inline tag, a style
	 * block or a doctype. The {@code \b} after the tag name keeps expressions like
	 * {@code 3 < 5} from matching.
	 */
	private static final Pattern HTML_MARKUP = Pattern.compile(
			"<\\s*/?\\s*(html|head|body|div|span|table|thead|tbody|tfoot|tr|td|th|p|br|hr|a|b|i"
					+ "|strong|em|u|img|ul|ol|li|h[1-6]|style|meta|link|font|center|blockquote"
					+ "|pre|code|title|section|article|!doctype)\\b",
			Pattern.CASE_INSENSITIVE);

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> col = mongo.getCollection("issues");
		// Ingested issues carry ingestConnectionId (and reporterEmail); both are set
		// only at ingest time, so this never rewrites a user-authored description.
		Document filter = new Document("$or", List.of(
				new Document("ingestConnectionId", new Document("$ne", null)),
				new Document("reporterEmail", new Document("$ne", null))));
		int migrated = 0;
		for (Document doc : col.find(filter)) {
			Object raw = doc.get("description");
			if (!(raw instanceof String description) || description.isBlank()) {
				continue;
			}
			String rebuilt = rebuild(description);
			if (rebuilt == null) {
				continue; // no HTML in the body -> already clean, skip (idempotent guard)
			}
			col.updateOne(new Document("_id", doc.get("_id")),
					new Document("$set", new Document("description", rebuilt)));
			migrated++;
		}
		if (migrated > 0) {
			log.info("IngestedHtmlDescriptionBackfill: converted HTML to Markdown in {} "
					+ "ingested issue description(s)", migrated);
		}
	}

	/**
	 * Converts the HTML body of an ingest description to Markdown, preserving the
	 * "Created from e-mail by ..." header. Returns {@code null} when the body holds no
	 * HTML markup — the signal that there is nothing to migrate (and the idempotency
	 * guard, since a converted body no longer matches).
	 */
	static String rebuild(String description) {
		int sep = description.indexOf(BODY_SEPARATOR);
		String header = sep >= 0 ? description.substring(0, sep + BODY_SEPARATOR.length()) : "";
		String body = sep >= 0 ? description.substring(sep + BODY_SEPARATOR.length()) : description;
		if (!HTML_MARKUP.matcher(body).find()) {
			return null;
		}
		String converted = HtmlToMarkdown.convert(body);
		if (converted.length() > MAX_BODY) {
			converted = converted.substring(0, MAX_BODY);
		}
		return header + converted;
	}
}
