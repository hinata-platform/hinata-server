package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads of a single issue as a document: {@code export.pdf},
 * {@code export.docx}, {@code export.xlsx} and {@code export.xml}. The suffix
 * carries the format, mirroring {@code /api/v1/me/export.pdf}, so a link is
 * self-describing and a browser saves it under a sensible name without being
 * told twice.
 *
 * <p>Four endpoints, one body: the format is the only thing that differs, and
 * every guard — the scope of what is included, the project ACL inside
 * {@link IssueExportService}, the per-caller budget, the audit entry — happens
 * once for all of them. Four copies of that is four places for one of them to be
 * forgotten.
 *
 * <p>Print is not an endpoint. The app fetches the PDF and hands it to the
 * platform's print dialog, so a printed issue and a saved one are the same
 * bytes and cannot drift apart.
 */
@Tag(name = "Issues")
@RestController
@RequestMapping("/api/v1/issues/{idOrReadableId}")
public class IssueExportController {

	private final IssueExportService exports;
	private final CurrentUser currentUser;
	private final ExportRateLimiter limiter;
	private final AuditService audit;
	private final Map<IssueExportFormat, IssueExportRenderer> byFormat;

	/**
	 * The renderers arrive as a list and are indexed here, once.
	 *
	 * <p>Indexed at construction rather than on first use because the list is
	 * already complete at construction — the laziness bought nothing and cost
	 * correctness: four endpoints share this bean and servlet threads reach it
	 * concurrently, so a map published through a plain field can be seen by
	 * another thread as a non-null reference to a map that is not finished being
	 * built. A final field written in the constructor is the one publication the
	 * memory model guarantees.
	 */
	public IssueExportController(IssueExportService exports, CurrentUser currentUser,
			ExportRateLimiter limiter, AuditService audit,
			List<IssueExportRenderer> renderers) {
		this.exports = exports;
		this.currentUser = currentUser;
		this.limiter = limiter;
		this.audit = audit;
		Map<IssueExportFormat, IssueExportRenderer> index =
				new EnumMap<>(IssueExportFormat.class);
		for (IssueExportRenderer renderer : renderers) {
			index.put(renderer.format(), renderer);
		}
		this.byFormat = Map.copyOf(index);
	}

	@Operation(summary = "Download this issue as a PDF")
	@GetMapping("/export.pdf")
	public ResponseEntity<byte[]> pdf(@PathVariable String idOrReadableId,
			@RequestParam(defaultValue = "true") boolean comments,
			@RequestParam(defaultValue = "true") boolean links,
			@RequestParam(defaultValue = "true") boolean attachments,
			@RequestParam(defaultValue = "false") boolean activity) {
		return export(IssueExportFormat.PDF, idOrReadableId,
				new IssueExport.Options(comments, links, attachments, activity));
	}

	@Operation(summary = "Download this issue as a Word document")
	@GetMapping("/export.docx")
	public ResponseEntity<byte[]> docx(@PathVariable String idOrReadableId,
			@RequestParam(defaultValue = "true") boolean comments,
			@RequestParam(defaultValue = "true") boolean links,
			@RequestParam(defaultValue = "true") boolean attachments,
			@RequestParam(defaultValue = "false") boolean activity) {
		return export(IssueExportFormat.DOCX, idOrReadableId,
				new IssueExport.Options(comments, links, attachments, activity));
	}

	@Operation(summary = "Download this issue as a spreadsheet")
	@GetMapping("/export.xlsx")
	public ResponseEntity<byte[]> xlsx(@PathVariable String idOrReadableId,
			@RequestParam(defaultValue = "true") boolean comments,
			@RequestParam(defaultValue = "true") boolean links,
			@RequestParam(defaultValue = "true") boolean attachments,
			@RequestParam(defaultValue = "false") boolean activity) {
		return export(IssueExportFormat.XLSX, idOrReadableId,
				new IssueExport.Options(comments, links, attachments, activity));
	}

	@Operation(summary = "Download this issue as XML")
	@GetMapping("/export.xml")
	public ResponseEntity<byte[]> xml(@PathVariable String idOrReadableId,
			@RequestParam(defaultValue = "true") boolean comments,
			@RequestParam(defaultValue = "true") boolean links,
			@RequestParam(defaultValue = "true") boolean attachments,
			@RequestParam(defaultValue = "false") boolean activity) {
		return export(IssueExportFormat.XML, idOrReadableId,
				new IssueExport.Options(comments, links, attachments, activity));
	}

	/**
	 * The one path every format takes: budget, gather (which is where the ACL
	 * lives), render, audit, answer.
	 */
	private ResponseEntity<byte[]> export(IssueExportFormat format, String idOrReadableId,
			IssueExport.Options options) {
		User user = currentUser.require();
		// Metered before the issue is read: an export is worth metering whether or
		// not the caller turns out to be allowed to have it.
		limiter.require(user.getId());
		IssueExport export = exports.gather(idOrReadableId, options, user);
		byte[] body = renderer(format).render(export);
		audit.event(AuditAction.ISSUE_EXPORTED).actor(user)
				.meta("issue", export.readableId())
				.meta("format", format.extension())
				.meta("bytes", String.valueOf(body.length))
				.log();
		String fileName = ExportText.fileNameStem(export.readableId(), export.title())
				+ "." + format.extension();
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(format.contentType()))
				// filename* per RFC 5987, so a title with umlauts survives; the value
				// itself is already stripped of anything that could end the header.
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(fileName, StandardCharsets.UTF_8).build().toString())
				// An export is a snapshot of a moving issue and may contain anything
				// the issue does; it has no business in a shared cache.
				.cacheControl(CacheControl.noStore())
				.header("X-Content-Type-Options", "nosniff")
				.body(body);
	}

	/**
	 * The renderer for [format]. Never null in a wired application — the guard is
	 * for the one way it could be: a format added to the enum without the bean
	 * that renders it, which is a 400 rather than a NullPointerException.
	 */
	private IssueExportRenderer renderer(IssueExportFormat format) {
		IssueExportRenderer renderer = byFormat.get(format);
		if (renderer == null) {
			throw ApiException.badRequest("error.issue.exportFailed");
		}
		return renderer;
	}
}
