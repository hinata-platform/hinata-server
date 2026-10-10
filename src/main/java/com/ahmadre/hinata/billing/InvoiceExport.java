package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.export.ExportBlock;
import com.ahmadre.hinata.issue.export.ExportDocument;
import com.ahmadre.hinata.issue.export.ExportDocumentRenderer;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportRateLimiter;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An invoice or credit note as a file: PDF, Word or Excel, through the renderers every export
 * here shares (stage 12), with the organisation's logo.
 *
 * <p>The words are the invoice's own language — the issuer's when the draft was written — not the
 * language of whoever downloads it: a German invoice is a German document. Budgeted per caller
 * like every export, and audited without its amounts or recipient. Cells that look like formulas
 * are defused by the spreadsheet renderer.
 */
@Slf4j
@Service
public class InvoiceExport {

	private final InvoiceService invoices;
	private final BillingNames names;
	private final BillingSettings settings;
	private final ExportRateLimiter limiter;
	private final AuditService audit;
	private final BrandLogoService brandLogo;
	private final Clock clock;
	private final Map<ExportFormat, ExportDocumentRenderer> renderers;

	public InvoiceExport(InvoiceService invoices, BillingNames names, BillingSettings settings,
			ExportRateLimiter limiter, AuditService audit, BrandLogoService brandLogo, Clock clock,
			List<ExportDocumentRenderer> renderers) {
		this.invoices = invoices;
		this.names = names;
		this.settings = settings;
		this.limiter = limiter;
		this.audit = audit;
		this.brandLogo = brandLogo;
		this.clock = clock;
		Map<ExportFormat, ExportDocumentRenderer> byFormat = new EnumMap<>(ExportFormat.class);
		renderers.forEach(renderer -> byFormat.put(renderer.format(), renderer));
		this.renderers = Map.copyOf(byFormat);
	}

	/** The record to export, read and budgeted before any byte is written. */
	public Invoice plan(User viewer, String id) {
		Invoice invoice = invoices.forExport(viewer, id);
		limiter.require(viewer.getId());
		return invoice;
	}

	/** The file name: the number, or "draft" with the record's id. */
	public static String fileName(Invoice invoice, ExportFormat format) {
		String base = invoice.getNumber() != null ? invoice.getNumber() : "draft-" + invoice.getId();
		return base + "." + format.extension();
	}

	public void write(User viewer, Invoice invoice, ExportFormat format, ExportWords words, OutputStream out) {
		ExportDocumentRenderer renderer = renderers.get(format);
		if (renderer == null) {
			throw ApiException.badRequest("error.issue.exportFailed");
		}
		boolean machine = format == ExportFormat.XLSX;
		boolean credit = invoice.getKind() == Invoice.Kind.CREDIT_NOTE;
		boolean draft = invoice.getStatus() != Invoice.Status.ISSUED;
		String kindKey = credit ? "creditNote" : draft ? "draft" : "invoice";

		List<ExportBlock> blocks = new ArrayList<>();
		List<ExportBlock.KeyValue> head = new ArrayList<>();
		if (invoice.getNumber() != null) {
			head.add(kv(words, "number", invoice.getNumber()));
		}
		if (invoice.getIssuedAt() != null) {
			head.add(kv(words, "issuedOn", words.date(LocalDate.ofInstant(invoice.getIssuedAt(), settings.zone()))));
		}
		head.add(kv(words, "period", words.date(invoice.getPeriodFrom()) + " – " + words.date(invoice.getPeriodTo())));
		BillingNames.Name project = names.projects(List.of(invoice.getProjectId())).get(invoice.getProjectId());
		if (project != null) {
			boolean readsProject = !names.readable(viewer.getId(), List.of(invoice.getProjectId())).isEmpty();
			head.add(kv(words, "project", readsProject && project.label() != null
					? project.detail() + " · " + project.label() : project.detail()));
		}
		if (credit && invoice.getCreditedInvoiceId() != null) {
			String reversed = invoices.numberOf(invoice.getCreditedInvoiceId());
			if (reversed != null) {
				head.add(kv(words, "reverses", reversed));
			}
		}
		blocks.add(new ExportBlock.KeyValues(head));

		Invoice.Recipient recipient = invoice.getRecipient();
		if (recipient != null && (recipient.name() != null || recipient.address() != null)) {
			blocks.add(new ExportBlock.Section(words.t("export.invoice.recipient")));
			List<ExportBlock.KeyValue> to = new ArrayList<>();
			if (recipient.name() != null) {
				to.add(kv(words, "recipientName", recipient.name()));
			}
			if (recipient.address() != null) {
				to.add(kv(words, "recipientAddress", recipient.address()));
			}
			if (recipient.vatId() != null) {
				to.add(kv(words, "vatId", recipient.vatId()));
			}
			blocks.add(new ExportBlock.KeyValues(to));
		}

		blocks.add(new ExportBlock.Section(words.t("export.invoice.lines")));
		List<String> headers = List.of(words.t("export.invoice.column.description"),
				words.t("export.invoice.column.hours"), words.t("export.invoice.column.rate"),
				words.t("export.invoice.column.amount"));
		List<List<String>> rows = new ArrayList<>();
		for (Invoice.Line line : invoice.getLines()) {
			rows.add(List.of(line.description() == null ? "" : line.description(),
					hours(line.minutes(), words, machine), money(line.rateCents(), invoice, words, machine),
					money(line.amountCents(), invoice, words, machine)));
		}
		blocks.add(new ExportBlock.Table(headers, rows, List.of(3.2f, 0.9f, 1.1f, 1.2f), Set.of(1, 2, 3)));

		Invoice.Totals totals = invoice.getTotals();
		List<ExportBlock.KeyValue> sums = new ArrayList<>();
		sums.add(kv(words, "hours", hours(totals.minutes(), words, machine)));
		sums.add(kv(words, "net", money(totals.netCents(), invoice, words, machine)));
		if (invoice.getTaxBasisPoints() > 0) {
			sums.add(new ExportBlock.KeyValue(words.t("export.invoice.tax", percent(invoice.getTaxBasisPoints(), words)),
					money(totals.taxCents(), invoice, words, machine)));
		}
		sums.add(kv(words, "gross", money(totals.grossCents(), invoice, words, machine)));
		blocks.add(new ExportBlock.KeyValues(sums));
		if (invoice.getNotes() != null) {
			blocks.add(new ExportBlock.Note(invoice.getNotes()));
		}
		if (draft) {
			blocks.add(new ExportBlock.Note(words.t("export.invoice.draftNote")));
		}

		String title = invoice.getNumber() != null
				? words.t("export.invoice.title." + kindKey, invoice.getNumber())
				: words.t("export.invoice.title.draft");
		renderer.render(new ExportDocument(words.t("export.invoice.eyebrow." + kindKey), title,
				recipient == null || recipient.name() == null ? "" : recipient.name(), blocks, settings.organization(),
				logo(), clock.instant(), words), out);
		audit.event(AuditAction.INVOICE_EXPORTED).actor(viewer).target(invoice.getId(), invoice.getNumber())
				.meta("format", format.extension())
				.meta("status", invoice.getStatus().name())
				.log();
	}

	private static ExportBlock.KeyValue kv(ExportWords words, String key, String value) {
		return new ExportBlock.KeyValue(words.t("export.invoice." + key), value == null ? "" : value);
	}

	/** Hours with two decimals: localized for a reader, a plain number for a spreadsheet. */
	static String hours(long minutes, ExportWords words, boolean machine) {
		BigDecimal value = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
		if (machine) {
			return value.toPlainString();
		}
		NumberFormat format = NumberFormat.getNumberInstance(words.locale());
		format.setMinimumFractionDigits(2);
		format.setMaximumFractionDigits(2);
		return format.format(value);
	}

	/** Cents in the invoice's currency: with its symbol for a reader, a plain number for a spreadsheet. */
	static String money(long cents, Invoice invoice, ExportWords words, boolean machine) {
		Currency currency = currency(invoice.getCurrency());
		int digits = Math.max(0, currency.getDefaultFractionDigits());
		BigDecimal value = BigDecimal.valueOf(cents, 2).setScale(digits, java.math.RoundingMode.HALF_UP);
		if (machine) {
			return value.toPlainString();
		}
		NumberFormat format = NumberFormat.getCurrencyInstance(words.locale());
		format.setCurrency(currency);
		return format.format(value);
	}

	private static String percent(int basisPoints, ExportWords words) {
		NumberFormat format = NumberFormat.getNumberInstance(words.locale());
		format.setMaximumFractionDigits(2);
		return format.format(BigDecimal.valueOf(basisPoints, 2));
	}

	private static Currency currency(String code) {
		try {
			return Currency.getInstance(code == null ? "EUR" : code);
		}
		catch (IllegalArgumentException unknown) {
			return Currency.getInstance("EUR");
		}
	}

	private byte[] logo() {
		try {
			return brandLogo.raster().orElse(null);
		}
		catch (RuntimeException ex) {
			log.warn("The organization logo was left out of an invoice: {}", ex.toString());
			return null;
		}
	}
}
