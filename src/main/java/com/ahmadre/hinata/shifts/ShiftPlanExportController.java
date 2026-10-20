package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * {@code GET /api/v1/shifts/events/{id}/export?format=pdf|docx|xlsx&names=false|true}: an event's
 * shift plan as a file (HIN-163), behind {@link ShiftPlanningGate}. See {@link ShiftPlanExport}.
 *
 * <p>Written straight into the servlet response, as the time report is: a sequential read with
 * nothing to wait on.
 */
@Tag(name = "Shift planning")
@RestController
@RequiredArgsConstructor
public class ShiftPlanExportController {

	/** Set when the file stops at its format's ceiling. */
	static final String TRUNCATED_HEADER = "X-Export-Truncated";

	private final ShiftPlanExport exports;
	private final CurrentUser currentUser;

	@Operation(summary = "An event's shift plan as PDF, Word document or workbook; names only for planners")
	@GetMapping("/api/v1/shifts/events/{id}/export")
	public void export(@PathVariable String id, @RequestParam(defaultValue = "pdf") String format,
			@RequestParam(defaultValue = "false") boolean names, HttpServletResponse response) throws IOException {
		ExportFormat parsed = format(format);
		User caller = currentUser.require();
		try (ShiftPlanExport.Plan plan = exports.plan(caller, id, parsed, names, LocaleContextHolder.getLocale())) {
			response.setContentType(parsed.contentType());
			response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
					.filename(plan.fileName(), StandardCharsets.UTF_8).build().toString());
			response.setHeader(TRUNCATED_HEADER, String.valueOf(plan.truncated()));
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			response.setHeader("X-Content-Type-Options", "nosniff");
			exports.write(plan, response.getOutputStream());
		}
	}

	private static ExportFormat format(String value) {
		try {
			return ExportFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("error.shifts.exportFormat");
		}
	}
}
