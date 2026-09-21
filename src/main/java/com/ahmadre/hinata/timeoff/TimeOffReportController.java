package com.ahmadre.hinata.timeoff;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * {@code /api/v1/time-off/report} — the report "absences and balances" and its files (HIN-119).
 * Who sees what is {@link TimeOffReportService}'s; this only reads the question off the request.
 *
 * <p>Behind {@link AbsenceManagementGate}, and 404 besides while {@code absenceReportsEnabled} is off.
 */
@Tag(name = "Absence management")
@RestController
@RequestMapping("/api/v1/time-off/report")
@RequiredArgsConstructor
public class TimeOffReportController {

	/** Set when the file stops at its format's ceiling. */
	static final String TRUNCATED_HEADER = "X-Export-Truncated";

	private final TimeOffReportService reports;
	private final TimeOffReportExport exports;
	private final CurrentUser currentUser;
	private final MessageSource messages;

	@Operation(summary = "Absences and balances for one leave year, per person or per type")
	@GetMapping
	public TimeOffReportService.Report report(@RequestParam(required = false) Integer year,
			@RequestParam(required = false) String typeId, @RequestParam(required = false) String teamId,
			@RequestParam(required = false) String projectId, @RequestParam(required = false) List<String> userIds,
			@RequestParam(required = false) TimeOffReportService.GroupBy groupBy,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return reports.report(currentUser.require(),
				new TimeOffReportService.ReportQuery(year, typeId, teamId, projectId, userIds, groupBy), page, size);
	}

	@Operation(summary = "The report as CSV")
	@GetMapping("/export.csv")
	public void csv(@RequestParam(required = false) Integer year, @RequestParam(required = false) String typeId,
			@RequestParam(required = false) String teamId, @RequestParam(required = false) String projectId,
			@RequestParam(required = false) List<String> userIds,
			@RequestParam(required = false) TimeOffReportService.GroupBy groupBy,
			HttpServletResponse response) throws IOException {
		User viewer = currentUser.require();
		TimeOffReportService.ReportQuery query =
				new TimeOffReportService.ReportQuery(year, typeId, teamId, projectId, userIds, groupBy);
		try (TimeOffReportExport.Plan plan = exports.plan(viewer, query, null)) {
			headers(response, "text/csv", plan);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			exports.writeCsv(plan, words(null), response.getOutputStream());
		}
	}

	@Operation(summary = "The report as a PDF")
	@GetMapping("/export.pdf")
	public void pdf(@RequestParam(required = false) Integer year, @RequestParam(required = false) String typeId,
			@RequestParam(required = false) String teamId, @RequestParam(required = false) String projectId,
			@RequestParam(required = false) List<String> userIds,
			@RequestParam(required = false) TimeOffReportService.GroupBy groupBy,
			HttpServletResponse response) throws IOException {
		document(new TimeOffReportService.ReportQuery(year, typeId, teamId, projectId, userIds, groupBy),
				ExportFormat.PDF, response);
	}

	@Operation(summary = "The report as a Word document")
	@GetMapping("/export.docx")
	public void docx(@RequestParam(required = false) Integer year, @RequestParam(required = false) String typeId,
			@RequestParam(required = false) String teamId, @RequestParam(required = false) String projectId,
			@RequestParam(required = false) List<String> userIds,
			@RequestParam(required = false) TimeOffReportService.GroupBy groupBy,
			HttpServletResponse response) throws IOException {
		document(new TimeOffReportService.ReportQuery(year, typeId, teamId, projectId, userIds, groupBy),
				ExportFormat.DOCX, response);
	}

	@Operation(summary = "The report as a workbook")
	@GetMapping("/export.xlsx")
	public void xlsx(@RequestParam(required = false) Integer year, @RequestParam(required = false) String typeId,
			@RequestParam(required = false) String teamId, @RequestParam(required = false) String projectId,
			@RequestParam(required = false) List<String> userIds,
			@RequestParam(required = false) TimeOffReportService.GroupBy groupBy,
			HttpServletResponse response) throws IOException {
		document(new TimeOffReportService.ReportQuery(year, typeId, teamId, projectId, userIds, groupBy),
				ExportFormat.XLSX, response);
	}

	private void document(TimeOffReportService.ReportQuery query, ExportFormat format, HttpServletResponse response)
			throws IOException {
		User viewer = currentUser.require();
		try (TimeOffReportExport.Plan plan = exports.plan(viewer, query, format)) {
			headers(response, format.contentType(), plan);
			exports.writeDocument(plan, format, words(format), response.getOutputStream());
		}
	}

	/**
	 * The words of the file: the language the app asked in, and for a PDF English where the page
	 * cannot draw that language's script — a complete report beats one with every label blank.
	 */
	private ExportWords words(ExportFormat format) {
		Locale asked = LocaleContextHolder.getLocale();
		Locale locale = format == ExportFormat.PDF
				? ExportFonts.renderableLocale(asked, messages.getMessage("export.absence.eyebrow", null, "", asked))
				: asked;
		return new ExportWords(messages, locale, ZoneOffset.UTC);
	}

	private static void headers(HttpServletResponse response, String contentType, TimeOffReportExport.Plan plan) {
		response.setContentType(contentType);
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename(plan.fileName(), StandardCharsets.UTF_8).build().toString());
		response.setHeader(TRUNCATED_HEADER, String.valueOf(plan.truncated()));
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader("X-Content-Type-Options", "nosniff");
	}
}
