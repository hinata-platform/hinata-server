package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.export.ExportFonts;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /api/v1/billing/invoices} (HIN-96). Behind {@link BillingGate}; for administrators and
 * the leads of the invoiced project.
 */
@Tag(name = "Billing")
@RestController
@RequestMapping("/api/v1/billing/invoices")
@RequiredArgsConstructor
public class InvoiceController {

	private final InvoiceService invoices;
	private final InvoiceExport exports;
	private final CurrentUser currentUser;
	private final MessageSource messages;

	public record RecipientBody(@Size(max = InvoiceService.TEXT_MAX) String name,
			@Size(max = InvoiceService.TEXT_MAX) String address, @Size(max = InvoiceService.TEXT_MAX) String vatId) {

		Invoice.Recipient value() {
			return new Invoice.Recipient(name, address, vatId);
		}
	}

	public record DraftBody(@Size(max = 64) String projectId, LocalDate from, LocalDate to,
			Invoice.Grouping grouping, @Valid RecipientBody recipient, @Size(max = InvoiceService.TEXT_MAX) String notes,
			Integer taxBasisPoints) {
	}

	public record ChangeBody(@Valid RecipientBody recipient, @Size(max = InvoiceService.TEXT_MAX) String notes,
			Integer taxBasisPoints, @Size(max = InvoiceService.ENTRIES_MAX) List<@Size(max = 64) String> removeLineIds,
			@Size(max = InvoiceService.ENTRIES_MAX) Map<String, String> lineDescriptions) {
	}

	public record CreditBody(@Size(max = InvoiceService.TEXT_MAX) String reason) {
	}

	@Operation(summary = "A page of invoices and credit notes, newest first")
	@GetMapping
	public Page<InvoiceService.Summary> page(@RequestParam(required = false) Invoice.Status status,
			@RequestParam(required = false) @Size(max = 64) String projectId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		return invoices.page(currentUser.require(), status, projectId, page, size);
	}

	@Operation(summary = "Draft an invoice from a project's unbilled billable entries in a period")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public InvoiceService.Detail create(@Valid @RequestBody DraftBody body) {
		return invoices.createDraft(currentUser.require(), new InvoiceService.DraftRequest(body.projectId(),
				body.from(), body.to(), body.grouping(), body.recipient() == null ? null : body.recipient().value(),
				body.notes(), body.taxBasisPoints()), LocaleContextHolder.getLocale());
	}

	@GetMapping("/{id}")
	public InvoiceService.Detail get(@PathVariable String id) {
		return invoices.get(currentUser.require(), id);
	}

	@Operation(summary = "One page of an invoice's lines")
	@GetMapping("/{id}/lines")
	public Page<InvoiceService.LineView> lines(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return invoices.lines(currentUser.require(), id, page, size);
	}

	@Operation(summary = "Change a draft: recipient, notes, tax, line texts, lines removed")
	@PatchMapping("/{id}")
	public InvoiceService.Detail update(@PathVariable String id, @Valid @RequestBody ChangeBody body) {
		return invoices.update(currentUser.require(), id, new InvoiceService.Change(
				body.recipient() == null ? null : body.recipient().value(), body.notes(), body.taxBasisPoints(),
				body.removeLineIds(), body.lineDescriptions()));
	}

	@Operation(summary = "Rebuild a draft's lines from its filter")
	@PostMapping("/{id}/refresh")
	public InvoiceService.Detail refresh(@PathVariable String id) {
		return invoices.refresh(currentUser.require(), id);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		invoices.delete(currentUser.require(), id);
	}

	@Operation(summary = "Issue a draft: number it, freeze it, mark its entries billed")
	@PostMapping("/{id}/issue")
	public InvoiceService.Detail issue(@PathVariable String id) {
		return invoices.issue(currentUser.require(), id);
	}

	@Operation(summary = "Reverse an issued invoice with a credit note")
	@PostMapping("/{id}/credit-note")
	public InvoiceService.Detail creditNote(@PathVariable String id,
			@Valid @RequestBody(required = false) CreditBody body) {
		return invoices.creditNote(currentUser.require(), id, body == null ? null : body.reason());
	}

	@GetMapping("/{id}/export.pdf")
	public void pdf(@PathVariable String id, HttpServletResponse response) throws IOException {
		export(id, ExportFormat.PDF, response);
	}

	@GetMapping("/{id}/export.docx")
	public void docx(@PathVariable String id, HttpServletResponse response) throws IOException {
		export(id, ExportFormat.DOCX, response);
	}

	@GetMapping("/{id}/export.xlsx")
	public void xlsx(@PathVariable String id, HttpServletResponse response) throws IOException {
		export(id, ExportFormat.XLSX, response);
	}

	private void export(String id, ExportFormat format, HttpServletResponse response) throws IOException {
		User viewer = currentUser.require();
		Invoice invoice = exports.plan(viewer, id);
		response.setContentType(format.contentType());
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename(InvoiceExport.fileName(invoice, format), StandardCharsets.UTF_8).build().toString());
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader("X-Content-Type-Options", "nosniff");
		exports.write(viewer, invoice, format, words(invoice, format), response.getOutputStream());
	}

	/**
	 * The invoice's own language; for a PDF English where the page cannot draw that language's
	 * script — a complete invoice beats one with every label blank.
	 */
	private ExportWords words(Invoice invoice, ExportFormat format) {
		Locale wanted = invoice.getLanguage() == null ? LocaleContextHolder.getLocale()
				: Locale.forLanguageTag(invoice.getLanguage());
		Locale locale = format == ExportFormat.PDF
				? ExportFonts.renderableLocale(wanted, messages.getMessage("export.invoice.eyebrow.invoice", null, "",
						wanted))
				: wanted;
		return new ExportWords(messages, locale, ZoneOffset.UTC);
	}
}
