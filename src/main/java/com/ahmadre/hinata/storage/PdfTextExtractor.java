package com.ahmadre.hinata.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Pulls the readable text out of a PDF, page by page, so a document attachment
 * can be reasoned about instead of merely listed.
 *
 * <p>Extraction stops as soon as [maxChars] is reached — a 400-page contract
 * costs the first few pages, not all of them. That bound is the whole point:
 * this text goes into a model's context window, and PDFBox will happily hand
 * back tens of megabytes of it.
 *
 * <p>A PDF is untrusted input and PDFBox parses a famously permissive format.
 * Everything hostile a file can do here is bounded the same way
 * {@link PdfPageRenderer} bounds rendering: a hard page ceiling, a hard
 * character ceiling, and any failure — malformed, encrypted, out of memory —
 * returns null rather than propagating.
 */
@Slf4j
final class PdfTextExtractor {

	/** Never walk further than this, however short the pages turn out to be. */
	private static final int MAX_PAGES = 50;

	private PdfTextExtractor() {
	}

	/** The extracted text and whether it stops short of the whole document. */
	record Extract(String text, boolean truncated) {
	}

	/** Text of the first pages, or null when the document cannot be read. */
	static Extract extract(byte[] pdf, int maxChars) {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			int total = document.getNumberOfPages();
			if (total < 1) {
				return null;
			}
			int lastPage = Math.min(total, MAX_PAGES);
			PDFTextStripper stripper = new PDFTextStripper();
			StringBuilder text = new StringBuilder();
			int page = 1;
			// Page at a time rather than one getText() over the document: the
			// budget is in characters, and the cheapest way to respect it is to
			// stop extracting once it is spent instead of extracting everything
			// and throwing most of it away.
			for (; page <= lastPage && text.length() < maxChars; page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				text.append(stripper.getText(document));
			}
			boolean truncated = text.length() > maxChars || page <= total;
			return new Extract(clamp(text, maxChars), truncated);
		}
		catch (Exception | OutOfMemoryError ex) {
			log.warn("PDF text extraction failed: {}", ex.toString());
			return null;
		}
	}

	private static String clamp(StringBuilder text, int maxChars) {
		return text.length() <= maxChars ? text.toString() : text.substring(0, maxChars);
	}
}
