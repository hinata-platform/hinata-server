package com.ahmadre.hinata.legal;

import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Seeds the bundled default legal documents (privacy policy + terms, de/en)
 * into object storage on boot — but only for documents that have no metadata
 * record yet, so an operator's customized text is never overwritten by a
 * redeploy. Without configured object storage this is a no-op; the public
 * endpoint then serves the classpath defaults directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegalSeeder implements ApplicationRunner {

	private final LegalDocumentRepository documents;
	private final LegalService legal;
	private final StorageService storage;

	@Override
	public void run(ApplicationArguments args) {
		if (!storage.isConfigured()) {
			log.info("Object storage not configured — legal documents served from bundled defaults");
			return;
		}
		for (String type : LegalService.TYPES) {
			for (String lang : LegalService.LANGS) {
				seedIfMissing(type, lang);
			}
		}
	}

	private void seedIfMissing(String type, String lang) {
		String id = LegalDocument.idFor(type, lang);
		if (documents.existsById(id)) {
			return;
		}
		try {
			String markdown = legal.classpathDefault(type, lang);
			String storageKey = LegalDocument.storageKeyFor(type, lang);
			storage.putObject(storageKey, markdown.getBytes(StandardCharsets.UTF_8), "text/markdown");
			documents.save(LegalDocument.builder()
					.id(id)
					.type(type)
					.lang(lang)
					.storageKey(storageKey)
					.source("seed")
					.updatedAt(Instant.now())
					.build());
			log.info("Seeded legal document {} into object storage", id);
		}
		catch (Exception ex) {
			// Boot must not fail over a legal-text seed; reads fall back to the
			// bundled classpath default until the next boot retries the seed.
			log.warn("Seeding legal document {} failed: {}", id, ex.getMessage());
		}
	}
}
