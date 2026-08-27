package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
import java.time.DateTimeException;
import java.time.ZoneId;
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
 *
 * <p>Every endpoint also takes {@code tz}, an IANA zone id. It is the one piece
 * of the reader's context the server cannot work out for itself: the language
 * arrives in {@code Accept-Language}, but the process runs with its clock nailed
 * to UTC so that stored instants are deterministic, and there is nothing in a
 * request that says where the person holding the phone is. Without it every
 * document was stamped in UTC, which is a time nobody's day is measured in.
 */
@Tag(name = "Issues")
@RestController
@RequestMapping("/api/v1/issues/{idOrReadableId}")
public class IssueExportController {

	private final IssueExportService exports;
	private final CurrentUser currentUser;
	private final ExportRateLimiter limiter;
	private final AuditService audit;
	private final MessageSource messages;
	private final SettingsService settings;
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
			ExportRateLimiter limiter, AuditService audit, MessageSource messages,
			SettingsService settings, List<IssueExportRenderer> renderers) {
		this.exports = exports;
		this.currentUser = currentUser;
		this.limiter = limiter;
		this.audit = audit;
		this.messages = messages;
		this.settings = settings;
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
			@RequestParam(defaultValue = "false") boolean activity,
			@Parameter(description = "IANA time zone the timestamps are written in, "
					+ "e.g. Europe/Berlin. Defaults to the organization's zone.")
			@RequestParam(required = false) String tz) {
		return export(IssueExportFormat.PDF, idOrReadableId,
				new IssueExport.Options(comments, links, attachments, activity), tz);
	}

	@Operation(summary = "Download this issue as a Word document")
	@GetMapping("/export.docx")
	public ResponseEntity<byte[]> docx(@PathVariable String idOrReadableId,
			@RequestParam(defaultValue = "true") boolean comments,
			@RequestParam(defaultValue = "true") boolean links,
			@RequestParam(defaultValue = "true") boolean attachments,
			@RequestParam(defaultValue = "false") boolean activity,
			@Parameter(description = "IANA time zone the timestamps are written in, "
					+ "e.g. Europe/Berlin. Defaults to the organization's zone.")
			@RequestParam(required = false) String tz) {
		return export(IssueExportFormat.DOCX, idOrReadableId,
				new IssueExport.Options(comments, links, attachments, activity), tz);
	}

	@Operation(summary = "Download this issue as a spreadsheet")
	@GetMapping("/export.xlsx")
	public ResponseEntity<byte[]> xlsx(@PathVariable String idOrReadableId,
			@RequestParam(defaultValue = "true") boolean comments,
			@RequestParam(defaultValue = "true") boolean links,
			@RequestParam(defaultValue = "true") boolean attachments,
			@RequestParam(defaultValue = "false") boolean activity,
			@Parameter(description = "IANA time zone the timestamps are written in, "
					+ "e.g. Europe/Berlin. Defaults to the organization's zone.")
			@RequestParam(required = false) String tz) {
		return export(IssueExportFormat.XLSX, idOrReadableId,
				new IssueExport.Options(comments, links, attachments, activity), tz);
	}

	@Operation(summary = "Download this issue as XML")
	@GetMapping("/export.xml")
	public ResponseEntity<byte[]> xml(@PathVariable String idOrReadableId,
			@RequestParam(defaultValue = "true") boolean comments,
			@RequestParam(defaultValue = "true") boolean links,
			@RequestParam(defaultValue = "true") boolean attachments,
			@RequestParam(defaultValue = "false") boolean activity,
			@Parameter(description = "IANA time zone the timestamps are written in, "
					+ "e.g. Europe/Berlin. Defaults to the organization's zone.")
			@RequestParam(required = false) String tz) {
		return export(IssueExportFormat.XML, idOrReadableId,
				new IssueExport.Options(comments, links, attachments, activity), tz);
	}

	/**
	 * The one path every format takes: budget, gather (which is where the ACL
	 * lives), render, audit, answer.
	 */
	private ResponseEntity<byte[]> export(IssueExportFormat format, String idOrReadableId,
			IssueExport.Options options, String tz) {
		User user = currentUser.require();
		// Metered before the issue is read: an export is worth metering whether or
		// not the caller turns out to be allowed to have it.
		limiter.require(user.getId());
		ExportWords words = new ExportWords(messages, LocaleContextHolder.getLocale(), zone(tz));
		IssueExport export = exports.gather(idOrReadableId, options, user, words);
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
	 * The zone the document's timestamps are written in: what the caller asked
	 * for, else the organization's configured zone, else UTC.
	 *
	 * <p>A zone id that {@code ZoneId} does not know is ignored rather than
	 * refused. It arrives from a device's own settings — a platform that reports
	 * a Windows zone name, or one this JDK's tzdb has not heard of yet — and
	 * answering a download with a 400 over it would turn a cosmetic difference
	 * into a broken button. The fallback is a zone somebody chose in the admin
	 * area, which is a better guess than UTC and a much better one than an error.
	 *
	 * <p>The value is bounded before it is parsed: it is written straight into a
	 * document, and a zone id is at most a few dozen characters.
	 */
	private ZoneId zone(String requested) {
		if (requested != null && !requested.isBlank() && requested.length() <= 64) {
			try {
				return ZoneId.of(requested.trim());
			}
			catch (DateTimeException ignored) {
				// Fall through to the configured zone.
			}
		}
		String configured = settings.get().getGeneral().getTimezone();
		if (configured != null && !configured.isBlank()) {
			try {
				return ZoneId.of(configured.trim());
			}
			catch (DateTimeException ignored) {
				// Fall through to UTC.
			}
		}
		return ZoneId.of("UTC");
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
