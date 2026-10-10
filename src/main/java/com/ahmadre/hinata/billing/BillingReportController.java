package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * {@code /api/v1/billing/reports/{billing|profitability|utilization}} (HIN-96). Behind
 * {@link BillingGate}; profitability for administrators only.
 */
@Tag(name = "Billing")
@RestController
@RequestMapping("/api/v1/billing/reports")
@RequiredArgsConstructor
public class BillingReportController {

	private final BillingReportService reports;
	private final CurrentUser currentUser;

	@Operation(summary = "Revenue, costs and margins or utilization over a window, grouped")
	@GetMapping("/{kind}")
	public BillingReportService.Report report(@PathVariable String kind,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) List<String> projectIds,
			@RequestParam(required = false) BillingReportService.GroupBy groupBy,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		BillingReportService.Kind which;
		try {
			which = BillingReportService.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			throw com.ahmadre.hinata.common.ApiException.notFound("billingReport");
		}
		return reports.report(currentUser.require(),
				new BillingReportService.ReportQuery(which, from, to, projectIds, groupBy), page, size);
	}
}
