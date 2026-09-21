package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.export.ExportBlock;
import com.ahmadre.hinata.issue.export.ExportDocument;
import com.ahmadre.hinata.issue.export.ExportDocumentRenderer;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportRateLimiter;
import com.ahmadre.hinata.issue.export.ExportText;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The report "absences and balances" as a file: CSV, PDF, Word or Excel (HIN-119).
 *
 * <p>The same answer the screen gets, from the same scope — a file is not a way around who may see
 * what. Budgeted per caller before anything is read, at most {@link #MAX_RUNNING} at once on the
 * instance, capped per format and marked when cut, and audited with a fingerprint of the question
 * rather than the names in it. Spreadsheet cells that look like formulas are defused, the way every
 * export here does it.
 */
@Slf4j
@Service
public class TimeOffReportExport {

	/** Most rows per format; a PDF of more is a stack of paper nobody reads. */
	static final Map<ExportFormat, Integer> MAX_ROWS = Map.of(ExportFormat.PDF, 5_000, ExportFormat.DOCX, 5_000,
			ExportFormat.XLSX, 50_000);

	static final int MAX_CSV_ROWS = 50_000;

	/** Exports running at once on this instance. */
	static final int MAX_RUNNING = 4;

	private final TimeOffReportService reports;
	private final TimeOffTypeRepository types;
	private final ExportRateLimiter limiter;
	private final AuditService audit;
	private final SettingsService settings;
	private final BrandLogoService brandLogo;
	private final Clock clock;
	private final Map<ExportFormat, ExportDocumentRenderer> renderers;
	private final Semaphore running = new Semaphore(MAX_RUNNING);

	public TimeOffReportExport(TimeOffReportService reports, TimeOffTypeRepository types, ExportRateLimiter limiter,
			AuditService audit, SettingsService settings, BrandLogoService brandLogo, Clock clock,
			List<ExportDocumentRenderer> renderers) {
		this.reports = reports;
		this.types = types;
		this.limiter = limiter;
		this.audit = audit;
		this.settings = settings;
		this.brandLogo = brandLogo;
		this.clock = clock;
		Map<ExportFormat, ExportDocumentRenderer> byFormat = new EnumMap<>(ExportFormat.class);
		renderers.forEach(renderer -> byFormat.put(renderer.format(), renderer));
		this.renderers = Map.copyOf(byFormat);
	}

	/** A file about to be written. Holds one of the {@link #MAX_RUNNING} slots until it is closed. */
	public record Plan(User viewer, TimeOffReportService.ReportQuery query, TimeOffReportService.Report report,
			boolean truncated, String fileName, Runnable release) implements AutoCloseable {

		@Override
		public void close() {
			release.run();
		}
	}

	/**
	 * Takes a slot and the caller's budget, then reads the report. [format] is null for CSV.
	 *
	 * @throws ApiException 429 when the instance is busy or the caller over budget
	 */
	public Plan plan(User viewer, TimeOffReportService.ReportQuery query, ExportFormat format) {
		if (!running.tryAcquire()) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.rateLimited");
		}
		AtomicBoolean released = new AtomicBoolean();
		Runnable release = () -> {
			if (released.compareAndSet(false, true)) {
				running.release();
			}
		};
		try {
			limiter.require(viewer.getId());
			int max = format == null ? MAX_CSV_ROWS : MAX_ROWS.get(format);
			// One more than fits, so a report that exactly fills the file is not called cut.
			TimeOffReportService.Report report = reports.forExport(viewer, query, max + 1);
			boolean truncated = report.rows().getContent().size() > max;
			String name = "absences-" + report.year() + "." + (format == null ? "csv" : format.extension());
			return new Plan(viewer, query, report, truncated, name, release);
		}
		catch (RuntimeException refused) {
			release.run();
			throw refused;
		}
	}

	/** The rows as CSV, a byte-order mark first so a spreadsheet reads the umlauts right. */
	public void writeCsv(Plan plan, ExportWords words, OutputStream target) throws IOException {
		Writer out = new BufferedWriter(new OutputStreamWriter(target, StandardCharsets.UTF_8));
		out.write('﻿');
		ExportText.csvRow(out, headers(plan, words));
		List<List<String>> rows = rows(plan, words, true);
		for (List<String> row : rows) {
			ExportText.csvRow(out, row);
		}
		if (plan.truncated()) {
			ExportText.csvRow(out, List.of(words.t("export.absence.truncated", rows.size())));
		}
		out.flush();
		audited(plan, "csv", rows.size());
	}

	/** The report as a document — head figures, then the rows — in [format]. */
	public void writeDocument(Plan plan, ExportFormat format, ExportWords words, OutputStream out) {
		ExportDocumentRenderer renderer = renderers.get(format);
		if (renderer == null) {
			throw ApiException.badRequest("error.issue.exportFailed");
		}
		boolean machine = format == ExportFormat.XLSX;
		List<List<String>> rows = rows(plan, words, machine);
		TimeOffReportService.Report report = plan.report();
		TimeOffReportService.Figures totals = report.totals();
		List<ExportBlock> blocks = new ArrayList<>();
		List<ExportBlock.KeyValue> head = new ArrayList<>();
		head.add(new ExportBlock.KeyValue(words.t("export.absence.people"), String.valueOf(report.people())));
		head.add(new ExportBlock.KeyValue(words.t("export.absence.column.remaining"),
				days(totals.remainingMilliDays(), words, machine)));
		head.add(new ExportBlock.KeyValue(words.t("export.absence.column.taken"),
				days(totals.takenMilliDays(), words, machine)));
		head.add(new ExportBlock.KeyValue(words.t("export.absence.column.planned"),
				days(totals.plannedMilliDays(), words, machine)));
		if (totals.ratePermille() != null) {
			head.add(new ExportBlock.KeyValue(words.t("export.absence.column.rate"),
					rate(totals.ratePermille(), words, machine)));
		}
		blocks.add(new ExportBlock.KeyValues(head));
		List<String> headers = headers(plan, words);
		List<Float> widths = new ArrayList<>();
		Set<Integer> endAligned = new java.util.HashSet<>();
		for (int i = 0; i < headers.size(); i++) {
			widths.add(i == 0 ? 2.4f : 1f);
			if (i > 0) {
				endAligned.add(i);
			}
		}
		blocks.add(new ExportBlock.Table(headers, rows, widths, endAligned));
		if (plan.truncated()) {
			blocks.add(new ExportBlock.Note(words.t("export.absence.truncated", rows.size())));
		}
		renderer.render(new ExportDocument(words.t("export.absence.eyebrow"),
				words.t("export.absence.title", report.year()), "", blocks, organization(), logo(), clock.instant(),
				words), out);
		audited(plan, format.extension(), rows.size());
	}

	private static List<String> headers(Plan plan, ExportWords words) {
		List<String> headers = new ArrayList<>();
		headers.add(words.t(plan.report().groupBy() == TimeOffReportService.GroupBy.PERSON
				? "export.absence.column.person" : "export.absence.column.type"));
		for (String column : List.of("entitled", "carriedIn", "taken", "planned", "remaining", "expiring",
				"expiringOn")) {
			headers.add(words.t("export.absence.column." + column));
		}
		if (plan.report().rateVisible()) {
			headers.add(words.t("export.absence.column.rate"));
		}
		return headers;
	}

	private List<List<String>> rows(Plan plan, ExportWords words, boolean machine) {
		Map<String, String> typeNames = new HashMap<>();
		for (TimeOffType type : types.findAll()) {
			typeNames.put(type.getId(), type.getName() != null && !type.getName().isBlank() ? type.getName()
					: words.t("timeOff.type." + (type.getSystemKey() == null ? "other" : type.getSystemKey())));
		}
		List<TimeOffReportService.Row> source = plan.report().rows().getContent();
		int max = plan.truncated() ? source.size() - 1 : source.size();
		List<List<String>> rows = new ArrayList<>();
		for (TimeOffReportService.Row row : source.subList(0, Math.max(0, max))) {
			TimeOffReportService.Figures figures = row.figures();
			List<String> cells = new ArrayList<>();
			cells.add(row.userId() != null ? nz(row.name()) : typeNames.getOrDefault(row.typeId(), ""));
			cells.add(days(figures.entitledMilliDays(), words, machine));
			cells.add(days(figures.carriedInMilliDays(), words, machine));
			cells.add(days(figures.takenMilliDays(), words, machine));
			cells.add(days(figures.plannedMilliDays(), words, machine));
			cells.add(days(figures.remainingMilliDays(), words, machine));
			cells.add(days(figures.expiringMilliDays(), words, machine));
			cells.add(figures.expiringOn() == null ? ""
					: machine ? figures.expiringOn().toString() : words.date(figures.expiringOn()));
			if (plan.report().rateVisible()) {
				cells.add(figures.ratePermille() == null ? "" : rate(figures.ratePermille(), words, machine));
			}
			rows.add(cells);
		}
		return rows;
	}

	/** Days with up to three decimals: a figure for a reader, a plain number for a spreadsheet. */
	static String days(int milliDays, ExportWords words, boolean machine) {
		BigDecimal value = BigDecimal.valueOf(milliDays, 3).stripTrailingZeros();
		if (machine) {
			return value.toPlainString();
		}
		NumberFormat format = NumberFormat.getNumberInstance(words.locale());
		format.setMaximumFractionDigits(3);
		return format.format(value);
	}

	private static String rate(int permille, ExportWords words, boolean machine) {
		BigDecimal percent = BigDecimal.valueOf(permille, 1).stripTrailingZeros();
		if (machine) {
			return percent.toPlainString();
		}
		NumberFormat format = NumberFormat.getNumberInstance(words.locale());
		format.setMaximumFractionDigits(1);
		return format.format(percent) + " %";
	}

	private void audited(Plan plan, String format, long rows) {
		audit.event(AuditAction.TIME_OFF_REPORT_EXPORTED).actor(plan.viewer())
				.meta("format", format)
				.meta("filter", hash(plan.query()))
				.meta("rows", String.valueOf(rows))
				.meta("truncated", String.valueOf(plan.truncated()))
				.log();
	}

	/** A fingerprint of the question, so two exports can be told apart without the log holding names. */
	static String hash(TimeOffReportService.ReportQuery query) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(String.valueOf(query).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 8);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private String organization() {
		try {
			String name = settings.get().getOrganizationName();
			return name == null ? "" : name.trim();
		}
		catch (RuntimeException ex) {
			return "";
		}
	}

	private byte[] logo() {
		try {
			return brandLogo.raster().orElse(null);
		}
		catch (RuntimeException ex) {
			log.warn("The organization logo was left out of an absence report: {}", ex.toString());
			return null;
		}
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}
}
