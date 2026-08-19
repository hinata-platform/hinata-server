package com.ahmadre.hinata.issue.export;

/**
 * One file format an issue can be exported to. Implementations receive a
 * finished {@link IssueExport} and do nothing but lay it out — no repository,
 * no formatting decisions about content, no idea which caller asked.
 */
public interface IssueExportRenderer {

	/** The format this renders, which is also the URL suffix and the extension. */
	IssueExportFormat format();

	byte[] render(IssueExport export);
}
