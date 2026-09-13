package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * {@code GET /api/v1/time/export.csv} — the caller's own entries as CSV. See
 * {@link TimeEntryCsvExport}.
 *
 * <p>Written straight into the servlet response rather than through an async
 * {@code StreamingResponseBody}: the export is a sequential read with nothing to
 * wait on, and an async dispatch would run the security filter chain a second time
 * after the body has already been committed.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time")
@RequiredArgsConstructor
public class TimeExportController {

	/** Set when the file stops at {@link TimeEntryCsvExport#MAX_ROWS} rows. */
	static final String TRUNCATED_HEADER = "X-Export-Truncated";

	private final TimeEntryCsvExport csv;
	private final CurrentUser currentUser;

	@GetMapping("/export.csv")
	public void exportCsv(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			HttpServletResponse response) throws IOException {
		// Closed when the file is written or the client goes away, which gives the
		// slot back; see TimeEntryCsvExport#MAX_RUNNING.
		try (TimeEntryCsvExport.Plan plan = csv.plan(from, to, currentUser.require())) {
			response.setContentType("text/csv");
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(plan.fileName()).build().toString());
			response.setHeader(TRUNCATED_HEADER, String.valueOf(plan.truncated()));
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			csv.write(plan, response.getOutputStream());
		}
	}
}
