package com.ahmadre.hinata.issue.export;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/**
 * The printable export of an issue, and what "Print" sends to the platform's print dialog —
 * so a printed issue and a saved PDF of it are the same bytes. The layout is the server's one
 * PDF layout ({@link PdfDocumentRenderer}); this only hands it the issue as a document.
 */
@Component
class PdfIssueExportRenderer implements IssueExportRenderer {

	private final PdfDocumentRenderer pdf = new PdfDocumentRenderer();

	@Override
	public IssueExportFormat format() {
		return IssueExportFormat.PDF;
	}

	@Override
	public byte[] render(IssueExport export) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		pdf.render(export.document(), out);
		return out.toByteArray();
	}
}
