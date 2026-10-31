package com.ahmadre.hinata.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telling a PDF that needs a password from one that does not (HIN-180). The fixtures are the
 * app's: one page, encrypted with AES-256, once with a user password and once with only an
 * owner password.
 */
class PdfPageRendererTest {

	static byte[] fixture(String name) throws IOException {
		try (InputStream in = PdfPageRendererTest.class.getResourceAsStream("/pdf/" + name + ".pdf")) {
			return in.readAllBytes();
		}
	}

	@Test
	void needsPassword_withAUserPassword_isTrue() throws IOException {
		assertThat(PdfPageRenderer.needsPassword(fixture("aes_256"))).isTrue();
	}

	@Test
	void needsPassword_withOnlyAnOwnerPassword_isFalse() throws IOException {
		// Opens without typing anything, like in every viewer: no lock for it.
		assertThat(PdfPageRenderer.needsPassword(fixture("owner_only"))).isFalse();
		assertThat(PdfPageRenderer.firstPage(fixture("owner_only"))).isNotNull();
	}

	@Test
	void needsPassword_forAPlainOrBrokenFile_isFalse() throws IOException {
		assertThat(PdfPageRenderer.needsPassword(fixture("plain"))).isFalse();
		assertThat(PdfPageRenderer.needsPassword("%PDF-1.7 not really".getBytes())).isFalse();
	}

	@Test
	void firstPage_withAUserPassword_isNull() throws IOException {
		assertThat(PdfPageRenderer.firstPage(fixture("aes_256"))).isNull();
	}
}
