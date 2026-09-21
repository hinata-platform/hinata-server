package com.ahmadre.hinata.issue.export;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/**
 * The Word export of an issue: the issue as a document ({@link IssueExport#document}), laid
 * out by the server's one Word layout ({@link DocxDocumentRenderer}).
 */
@Component
class DocxIssueExportRenderer implements IssueExportRenderer {

	private final DocxDocumentRenderer docx = new DocxDocumentRenderer();

	@Override
	public IssueExportFormat format() {
		return IssueExportFormat.DOCX;
	}

	@Override
	public byte[] render(IssueExport export) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		docx.render(export.document(), out);
		return out.toByteArray();
	}
}
