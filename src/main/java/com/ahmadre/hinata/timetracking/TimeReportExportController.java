package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.export.ExportFonts;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * {@code GET /api/v1/time/reports/export.{csv|xlsx|pdf}} — a report as a file (HIN-93), with the
 * filters of the report routes. See {@link TimeReportExport}.
 *
 * <p>Written straight into the servlet response rather than through an async body: the export is
 * a sequential read with nothing to wait on, and an async dispatch would run the security filter
 * chain a second time after the body is committed.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/reports")
@RequiredArgsConstructor
public class TimeReportExportController {

	/** Set when the file stops at its format's ceiling. */
	static final String TRUNCATED_HEADER = "X-Export-Truncated";

	private final TimeReportExport exports;
	private final TimeReportService reports;
	private final CurrentUser currentUser;
	private final MessageSource messages;

	@Operation(summary = "The report's entries as CSV")
	@GetMapping("/export.csv")
	public void csv(@ModelAttribute TimeReportQuery query, HttpServletResponse response) throws IOException {
		User viewer = currentUser.require();
		TimeReportFilter filter = reports.filter(viewer, query);
		try (TimeReportExport.Plan plan = exports.plan(viewer, filter, TimeReportService.GroupBy.PROJECT, null)) {
			headers(response, "text/csv", plan);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			exports.writeCsv(plan, words(null, filter), response.getOutputStream());
		}
	}

	@Operation(summary = "The report as a workbook: totals, groups and entries")
	@GetMapping("/export.xlsx")
	public void xlsx(@ModelAttribute TimeReportQuery query,
			@RequestParam(defaultValue = "PROJECT") TimeReportService.GroupBy groupBy,
			HttpServletResponse response) throws IOException {
		document(query, groupBy, ExportFormat.XLSX, response);
	}

	@Operation(summary = "The report as a PDF: totals, groups and entries")
	@GetMapping("/export.pdf")
	public void pdf(@ModelAttribute TimeReportQuery query,
			@RequestParam(defaultValue = "PROJECT") TimeReportService.GroupBy groupBy,
			HttpServletResponse response) throws IOException {
		document(query, groupBy, ExportFormat.PDF, response);
	}

	private void document(TimeReportQuery query, TimeReportService.GroupBy groupBy, ExportFormat format,
			HttpServletResponse response) throws IOException {
		User viewer = currentUser.require();
		TimeReportFilter filter = reports.filter(viewer, query);
		try (TimeReportExport.Plan plan = exports.plan(viewer, filter, groupBy, format)) {
			headers(response, format.contentType(), plan);
			exports.writeDocument(plan, format, words(format, filter), response.getOutputStream());
		}
	}

	/**
	 * The words of the file: the language the app asked in, and for a PDF English where the page
	 * cannot draw that language's script — a complete report beats one with every label blank.
	 */
	private ExportWords words(ExportFormat format, TimeReportFilter filter) {
		Locale asked = LocaleContextHolder.getLocale();
		Locale locale = format == ExportFormat.PDF
				? ExportFonts.renderableLocale(asked, messages.getMessage("export.report.eyebrow", null, "", asked))
				: asked;
		return new ExportWords(messages, locale, filter.zone());
	}

	private static void headers(HttpServletResponse response, String contentType, TimeReportExport.Plan plan) {
		response.setContentType(contentType);
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename(plan.fileName(), StandardCharsets.UTF_8).build().toString());
		response.setHeader(TRUNCATED_HEADER, String.valueOf(plan.truncated()));
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader("X-Content-Type-Options", "nosniff");
	}
}
