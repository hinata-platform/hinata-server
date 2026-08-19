package com.ahmadre.hinata.issue.export;

/**
 * The formats a single issue exports to. The name is the URL suffix and the file
 * extension, so {@code /issues/HIN-42/export.docx} and {@code HIN-42-….docx}
 * both come from here rather than from a literal repeated per endpoint.
 */
public enum IssueExportFormat {

	PDF("application/pdf"),
	DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
	XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
	XML("application/xml");

	private final String contentType;

	IssueExportFormat(String contentType) {
		this.contentType = contentType;
	}

	public String contentType() {
		return contentType;
	}

	/** The file extension, which is the enum name lower-cased. */
	public String extension() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
