package com.ahmadre.hinata.legal;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalServiceTest {

	private LegalDocumentRepository documents;
	private StorageService storage;
	private LegalService service;

	@BeforeEach
	void setUp() {
		documents = mock(LegalDocumentRepository.class);
		storage = mock(StorageService.class);
		service = new LegalService(documents, storage);
	}

	@Test
	void servesBundledDefaultWhenStorageNotConfigured() {
		when(documents.findById(anyString())).thenReturn(Optional.empty());
		when(storage.isConfigured()).thenReturn(false);

		LegalService.LegalContent content = service.get("privacy", "de");

		assertThat(content.lang()).isEqualTo("de");
		assertThat(content.markdown()).contains("Datenschutzerklärung");
		assertThat(content.updatedAt()).isNull();
	}

	@Test
	void unknownLanguageFallsBackToEnglish() {
		when(documents.findById(anyString())).thenReturn(Optional.empty());
		when(storage.isConfigured()).thenReturn(false);

		LegalService.LegalContent content = service.get("terms", "fr");

		assertThat(content.lang()).isEqualTo("en");
		assertThat(content.markdown()).contains("Terms of Service");
	}

	@Test
	void unknownTypeIsNotFound() {
		assertThatThrownBy(() -> service.get("../secrets", "de"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void prefersOperatorCopyFromStorage() {
		LegalDocument meta = LegalDocument.builder()
				.id("privacy.de")
				.type("privacy")
				.lang("de")
				.storageKey("legal/privacy.de.md")
				.updatedAt(Instant.parse("2026-07-01T00:00:00Z"))
				.build();
		when(documents.findById("privacy.de")).thenReturn(Optional.of(meta));
		when(storage.isConfigured()).thenReturn(true);
		when(storage.getObject("legal/privacy.de.md")).thenReturn(Optional.of(
				new StorageService.StoredObject(
						"# Angepasst vom Betreiber".getBytes(StandardCharsets.UTF_8),
						"text/markdown")));

		LegalService.LegalContent content = service.get("privacy", "de");

		assertThat(content.markdown()).isEqualTo("# Angepasst vom Betreiber");
		assertThat(content.updatedAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
	}

	@Test
	void storageOutageFallsBackToBundledDefault() {
		LegalDocument meta = LegalDocument.builder()
				.id("privacy.de").type("privacy").lang("de")
				.storageKey("legal/privacy.de.md").build();
		when(documents.findById("privacy.de")).thenReturn(Optional.of(meta));
		when(storage.isConfigured()).thenReturn(true);
		when(storage.getObject(anyString()))
				.thenThrow(new RuntimeException("storage down"));

		LegalService.LegalContent content = service.get("privacy", "de");

		assertThat(content.markdown()).contains("Datenschutzerklärung");
	}

	@Test
	void updateRejectsBlankContent() {
		assertThatThrownBy(() -> service.update("privacy", "de", "  ", "admin-1"))
				.isInstanceOf(ApiException.class);
	}
}
