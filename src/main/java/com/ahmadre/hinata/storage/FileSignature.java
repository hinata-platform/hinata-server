package com.ahmadre.hinata.storage;

/**
 * What a payload actually is, read from its own leading bytes rather than from
 * the content type somebody declared for it. The declared type is the one thing
 * an uploader picks, and it is what decides which parser a file is handed to —
 * so a ZIP labelled {@code application/pdf} must not reach PDFBox on the
 * strength of its own metadata.
 */
final class FileSignature {

	private FileSignature() {
	}

	/** {@code %PDF-} — the only signature a PDF is allowed to start with. */
	static boolean isPdf(byte[] data) {
		return data != null && data.length > 4
				&& data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
	}
}
