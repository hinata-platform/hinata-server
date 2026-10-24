package com.ahmadre.hinata.storage;

/**
 * What a payload actually is, read from its own leading bytes rather than from
 * the content type somebody declared for it. The declared type is the one thing
 * an uploader picks, and it is what decides which parser a file is handed to —
 * so a ZIP labelled {@code application/pdf} must not reach PDFBox on the
 * strength of its own metadata.
 */
final class FileSignature {

	/** How many leading bytes every signature here needs at most. */
	static final int HEAD = 16;

	/** The families of files that can be told by their first bytes. */
	enum Family {
		PNG, JPEG, GIF, WEBP, BMP, PDF, ZIP, MP4, AAC, MP3, WEBM, OGG, WAV
	}

	private FileSignature() {
	}

	/** {@code %PDF-} — the only signature a PDF is allowed to start with. */
	static boolean isPdf(byte[] data) {
		return data != null && data.length > 4
				&& data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
	}

	/** The family [head] starts like, or null when it starts like none of them (text, or anything else). */
	static Family detect(byte[] head) {
		if (startsWith(head, 0x89, 0x50, 0x4E, 0x47)) return Family.PNG;
		if (startsWith(head, 0xFF, 0xD8, 0xFF)) return Family.JPEG;
		if (startsWith(head, 0x47, 0x49, 0x46, 0x38)) return Family.GIF;
		if (riff(head, "WEBP")) return Family.WEBP;
		if (riff(head, "WAVE")) return Family.WAV;
		if (startsWith(head, 0x42, 0x4D) && head.length >= 6) return Family.BMP;
		if (isPdf(head)) return Family.PDF;
		if (startsWith(head, 0x50, 0x4B, 0x03, 0x04) || startsWith(head, 0x50, 0x4B, 0x05, 0x06)) return Family.ZIP;
		if (head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return Family.MP4;
		if (startsWith(head, 0x1A, 0x45, 0xDF, 0xA3)) return Family.WEBM;
		if (startsWith(head, 'O', 'g', 'g', 'S')) return Family.OGG;
		if (startsWith(head, 'I', 'D', '3')) return Family.MP3;
		// An MPEG audio frame sync, layer bits not 00: 1111 1111 111x x yy z with yy != 00.
		if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0 && (head[1] & 0x06) != 0) {
			return Family.MP3;
		}
		// An AAC frame in ADTS: the same sync with layer 00.
		if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xF6) == 0xF0) return Family.AAC;
		return null;
	}

	/** Whether [head] reads like text: no NUL byte, which no text format has and every binary one does. */
	static boolean looksLikeText(byte[] head) {
		for (byte b : head) {
			if (b == 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean riff(byte[] head, String form) {
		return head.length >= 12 && startsWith(head, 'R', 'I', 'F', 'F')
				&& head[8] == form.charAt(0) && head[9] == form.charAt(1) && head[10] == form.charAt(2)
				&& head[11] == form.charAt(3);
	}

	private static boolean startsWith(byte[] data, int... prefix) {
		if (data == null || data.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if ((data[i] & 0xFF) != prefix[i]) {
				return false;
			}
		}
		return true;
	}
}
