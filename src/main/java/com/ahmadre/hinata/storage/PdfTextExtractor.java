package com.ahmadre.hinata.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;

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
 * character ceiling, a hard ceiling on the glyphs a single page may cost, and
 * any failure — malformed, encrypted, out of memory — returns null rather than
 * propagating.
 */
@Slf4j
final class PdfTextExtractor {

	/** Never walk further than this, however short the pages turn out to be. */
	private static final int MAX_PAGES = 50;

	/**
	 * Glyphs a whole extraction may process, per character it is allowed to
	 * return. PDFBox lays out an entire page in memory <em>before</em> a single
	 * character is written, so the per-page character check in the loop below
	 * never sees a page coming: one 5 MB file whose compressed content stream
	 * expands into a few hundred million glyphs would take the heap with it while
	 * still on page one. A stripper drops far more than it emits (whitespace,
	 * layout, duplicate overprints), so four glyphs per requested character is
	 * generous for a real document and still orders of magnitude short of a bomb.
	 */
	private static final int GLYPHS_PER_CHAR = 4;

	/** Floor under that budget, so a small character budget still reads a page. */
	private static final long GLYPH_HEADROOM = 10_000;

	private PdfTextExtractor() {
	}

	/** The extracted text and whether it stops short of the whole document. */
	record Extract(String text, boolean truncated) {
	}

	/** Text of the first pages, or null when the document cannot be read. */
	static Extract extract(byte[] pdf, int maxChars) {
		return extract(pdf, maxChars, (long) Math.max(maxChars, 0) * GLYPHS_PER_CHAR + GLYPH_HEADROOM);
	}

	/**
	 * As {@link #extract(byte[], int)} but with an explicit glyph budget, so the
	 * bound that only a hostile document would ever reach can be exercised by a
	 * test with an ordinary one.
	 */
	static Extract extract(byte[] pdf, int maxChars, long glyphBudget) {
		StringBuilder text = new StringBuilder();
		try (PDDocument document = Loader.loadPDF(pdf)) {
			int total = document.getNumberOfPages();
			if (total < 1) {
				return null;
			}
			int lastPage = Math.min(total, MAX_PAGES);
			BoundedStripper stripper = new BoundedStripper(glyphBudget);
			int page = 1;
			// Page at a time rather than one getText() over the document: the
			// budget is in characters, and the cheapest way to respect it is to
			// stop extracting once it is spent instead of extracting everything
			// and throwing most of it away.
			try {
				for (; page <= lastPage && text.length() < maxChars; page++) {
					stripper.setStartPage(page);
					stripper.setEndPage(page);
					text.append(stripper.getText(document));
				}
			}
			catch (GlyphBudgetSpent spent) {
				// A page that costs more than the whole document's budget. Keep the
				// pages already in hand and say the extract stops short — answering
				// "unreadable" would be a lie about a document that was merely huge.
				return new Extract(clamp(text, maxChars), true);
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

	/**
	 * Abandons a page that has already cost more than it may. Control flow, not a
	 * fault, so it carries neither a stack trace nor a message.
	 */
	private static final class GlyphBudgetSpent extends RuntimeException {
		GlyphBudgetSpent() {
			super(null, null, false, false);
		}
	}

	/**
	 * A stripper that gives up once it has laid out more glyphs than the budget
	 * allows. This is the only place the cost of a page can be observed at all:
	 * every other bound here is on the text that comes out, and by then the page
	 * that produced it is already in memory.
	 */
	private static final class BoundedStripper extends PDFTextStripper {

		private final long budget;
		private long glyphs;

		BoundedStripper(long budget) throws IOException {
			this.budget = budget;
		}

		@Override
		protected void processTextPosition(TextPosition text) {
			if (++glyphs > budget) {
				throw new GlyphBudgetSpent();
			}
			super.processTextPosition(text);
		}
	}
}
