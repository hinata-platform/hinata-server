package com.ahmadre.hinata.issue.export;

import java.util.Locale;

/**
 * The formats a document of this platform is rendered to — a timesheet, a report, a data
 * export. The name is the URL suffix and the file extension, as with
 * {@link IssueExportFormat}, which keeps its own list because an issue also exports to XML.
 */
public enum ExportFormat {

	PDF("application/pdf"),
	DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
	XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	private final String contentType;

	ExportFormat(String contentType) {
		this.contentType = contentType;
	}

	public String contentType() {
		return contentType;
	}

	/** The file extension, which is the enum name lower-cased. */
	public String extension() {
		return name().toLowerCase(Locale.ROOT);
	}
}
