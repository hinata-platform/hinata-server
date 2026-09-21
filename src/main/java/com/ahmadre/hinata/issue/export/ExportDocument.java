package com.ahmadre.hinata.issue.export;

import java.time.Instant;
import java.util.List;

/**
 * A document this platform hands somebody to read, file or print, in the one shape every
 * renderer lays out: a letterhead, a title, and a flat list of {@link ExportBlock}s.
 *
 * <p>An issue, a timesheet, a time report, later an invoice and a shift plan all end here,
 * and {@link ExportDocumentRenderer} turns it into a PDF, a Word document or a workbook.
 * That is the point of it: there is one PDF layout on this server, so a printed report and
 * a printed issue share a letterhead, a typeface and the fonts that draw every script the
 * platform ships a language for, and a fix to one is a fix to all.
 *
 * @param eyebrow   a short line above the title — an issue key — or blank
 * @param subtitle  a quiet line under the title, or blank
 * @param logo      the organization's mark as PNG bytes, or {@code null}; {@code null} is
 *                  the ordinary case and every renderer produces its document without it
 * @param words     the language and the clock the document is written in; see
 *                  {@link ExportWords}
 */
public record ExportDocument(
		String eyebrow,
		String title,
		String subtitle,
		List<ExportBlock> blocks,
		String organization,
		byte[] logo,
		Instant generatedAt,
		ExportWords words) {

	/** What the footer names: the organization, or the product on an instance without one. */
	public String issuer() {
		return organization == null || organization.isBlank() ? "hinata" : organization;
	}
}
