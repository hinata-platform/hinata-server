package com.ahmadre.hinata.legal;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Serves and manages the legal documents (privacy policy / terms of service).
 *
 * <p>Resolution order for a read: object storage (operator-replaceable copy,
 * tracked by a {@link LegalDocument} metadata record) → bundled classpath
 * default ({@code legal/{type}.{lang}.md}). The classpath fallback keeps the
 * endpoint working on instances that run without configured object storage.
 *
 * <p>Types and languages are strictly whitelisted — they are used to build
 * storage keys and resource paths, so nothing user-controlled may pass through
 * unvalidated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalService {

	static final Set<String> TYPES = Set.of("privacy", "terms");
	static final Set<String> LANGS = Set.of("de", "en");
	private static final String FALLBACK_LANG = "en";

	private final LegalDocumentRepository documents;
	private final StorageService storage;

	/** A resolved legal document ready to serve. */
	public record LegalContent(String type, String lang, String markdown, Instant updatedAt) {
	}

	/**
	 * Loads the document, preferring the operator-managed copy in object
	 * storage. Unknown languages fall back to English; unknown types are a 404.
	 */
	public LegalContent get(String type, String lang) {
		requireType(type);
		String effectiveLang = LANGS.contains(lang) ? lang : FALLBACK_LANG;

		Optional<LegalDocument> meta = documents.findById(LegalDocument.idFor(type, effectiveLang));
		if (meta.isPresent() && storage.isConfigured()) {
			Optional<StorageService.StoredObject> stored = readStoredQuietly(meta.get().getStorageKey());
			if (stored.isPresent()) {
				return new LegalContent(type, effectiveLang,
						new String(stored.get().data(), StandardCharsets.UTF_8),
						meta.get().getUpdatedAt());
			}
		}
		return new LegalContent(type, effectiveLang, classpathDefault(type, effectiveLang), null);
	}

	/**
	 * Replaces a document's markdown: writes the new content to object storage
	 * and upserts the metadata record. Requires configured object storage.
	 */
	public LegalDocument update(String type, String lang, String markdown, String updatedBy) {
		requireType(type);
		if (!LANGS.contains(lang)) {
			throw ApiException.badRequest("error.legal.unknownLanguage");
		}
		if (markdown == null || markdown.isBlank()) {
			throw ApiException.badRequest("error.legal.emptyContent");
		}
		String storageKey = LegalDocument.storageKeyFor(type, lang);
		storage.putObject(storageKey, markdown.getBytes(StandardCharsets.UTF_8), "text/markdown");
		LegalDocument doc = documents.findById(LegalDocument.idFor(type, lang))
				.orElseGet(() -> LegalDocument.builder()
						.id(LegalDocument.idFor(type, lang))
						.type(type)
						.lang(lang)
						.storageKey(storageKey)
						.build());
		doc.setSource("admin");
		doc.setUpdatedAt(Instant.now());
		doc.setUpdatedBy(updatedBy);
		return documents.save(doc);
	}

	/** Reads the bundled default shipped with the server build. */
	String classpathDefault(String type, String lang) {
		requireType(type);
		var resource = new ClassPathResource("legal/" + type + "." + lang + ".md");
		try (var stream = resource.getInputStream()) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			log.error("Bundled legal default legal/{}.{}.md unreadable: {}", type, lang, ex.getMessage());
			throw ApiException.notFound("legalDocument");
		}
	}

	/**
	 * Storage read that degrades to the classpath fallback instead of failing
	 * the public endpoint when the object store is briefly unavailable.
	 */
	private Optional<StorageService.StoredObject> readStoredQuietly(String storageKey) {
		try {
			return storage.getObject(storageKey);
		}
		catch (Exception ex) {
			log.warn("Legal document {} unreadable from storage, serving bundled default: {}",
					storageKey, ex.getMessage());
			return Optional.empty();
		}
	}

	private static void requireType(String type) {
		if (!TYPES.contains(type)) {
			throw ApiException.notFound("legalDocument");
		}
	}
}
