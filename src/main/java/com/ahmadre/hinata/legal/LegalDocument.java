package com.ahmadre.hinata.legal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Metadata for one legal document (privacy policy / terms of service) in one
 * language. The markdown content itself lives in object storage under
 * {@link #storageKey}; this record only tracks where it is and when it last
 * changed, so operators can swap the text without a redeploy.
 *
 * <p>The id is deterministic ({@code type.lang}, e.g. {@code privacy.de}) so
 * seeding and updates are idempotent upserts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("legal_documents")
public class LegalDocument {

	/** {@code type.lang}, e.g. {@code privacy.de}. */
	@Id
	private String id;

	/** Document type: {@code privacy} or {@code terms}. */
	private String type;

	/** ISO language code: {@code de} or {@code en}. */
	private String lang;

	/** Object-storage key of the markdown file, e.g. {@code legal/privacy.de.md}. */
	private String storageKey;

	/** Where the current content came from: {@code seed} or {@code admin}. */
	private String source;

	private Instant updatedAt;

	/** User id of the admin who last replaced the content (null for seeds). */
	private String updatedBy;

	public static String idFor(String type, String lang) {
		return type + "." + lang;
	}

	public static String storageKeyFor(String type, String lang) {
		return "legal/" + type + "." + lang + ".md";
	}
}
