package com.ahmadre.hinata.issue.export;

import java.io.OutputStream;

/**
 * One file format an {@link ExportDocument} is laid out in. Implementations place what they
 * are given and decide nothing about content: no repository, no idea who asked.
 */
public interface ExportDocumentRenderer {

	ExportFormat format();

	/**
	 * Writes [document] to [out]. The stream is left open: the caller owns it, and for a
	 * download it is the response.
	 */
	void render(ExportDocument document, OutputStream out);
}
