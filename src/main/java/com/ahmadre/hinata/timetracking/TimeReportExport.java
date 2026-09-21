package com.ahmadre.hinata.timetracking;

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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * A time report as a file (HIN-93): CSV, a workbook or a PDF, with the filter and scope of the
 * report on screen.
 *
 * <p>Read from the database cursor in chunks of {@link #CHUNK} and written as it is read, so a
 * file of a hundred thousand entries never sits in memory: the CSV streams straight into the
 * response, the workbook through POI's streaming sheets, the PDF through a table added to the page
 * in parts. Each format stops at its own ceiling ({@link #MAX_ROWS}) and says so in the file and in
 * {@code X-Export-Truncated}: a PDF of five thousand rows is already a stack of paper nobody reads,
 * a workbook past fifty thousand opens slowly anywhere.
 *
 * <p>The order of the checks is the export's defence: the request's values first (a caller's
 * mistake costs nothing), then a slot among {@link #MAX_RUNNING} exports at once (a busy instance
 * is nobody's fault), then the caller's budget ({@link ExportRateLimiter}), and only then the
 * reader's scope — metered before anything is read, the way the issue export is.
 *
 * <p>Every file is audited ({@link AuditAction#TIME_REPORT_EXPORTED}) with its format, a hash of
 * the filter and the number of rows. The personal CSV of {@code /api/v1/time/export.csv} stays
 * what it is: the owner's own entries, the portability export the documentation promises.
 */
@Slf4j
@Service
public class TimeReportExport {

	/** Rows each format holds at most. */
	static final Map<ExportFormat, Integer> MAX_ROWS = Map.of(ExportFormat.PDF, 5_000, ExportFormat.XLSX, 50_000);

	/** Rows the CSV holds at most. */
	static final int MAX_CSV_ROWS = 100_000;

	/** Entries read, named and written per round trip. */
	static final int CHUNK = 500;

	/** Exports rendered at once across the instance. */
	static final int MAX_RUNNING = 4;

	/** Groups a PDF or workbook summarises; the rest is one line saying how many. */
	static final int SUMMARY_GROUPS = TimeReportService.PAGE_MAX;

	private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final TimeReportService reports;
	private final MongoTemplate mongo;
	private final ExportRateLimiter limiter;
	private final AuditService audit;
	private final SettingsService settings;
	private final BrandLogoService brandLogo;
	private final Clock clock;
	private final Map<ExportFormat, ExportDocumentRenderer> renderers;
	private final Semaphore running = new Semaphore(MAX_RUNNING);

	public TimeReportExport(TimeReportService reports, MongoTemplate mongo, ExportRateLimiter limiter,
			AuditService audit, SettingsService settings, BrandLogoService brandLogo, Clock clock,
			List<ExportDocumentRenderer> renderers) {
		this.reports = reports;
		this.mongo = mongo;
		this.limiter = limiter;
		this.audit = audit;
		this.settings = settings;
		this.brandLogo = brandLogo;
		this.clock = clock;
		Map<ExportFormat, ExportDocumentRenderer> byFormat = new EnumMap<>(ExportFormat.class);
		renderers.forEach(renderer -> byFormat.put(renderer.format(), renderer));
		this.renderers = Map.copyOf(byFormat);
	}

	/**
	 * A file about to be written: what it reads, whether it will be cut short, its name. Holds one
	 * of the {@link #MAX_RUNNING} slots until it is closed.
	 */
	public record Plan(User viewer, TimeReportFilter filter, TimeReportService.GroupBy groupBy, Query query,
			int maxRows, boolean truncated, String fileName, Runnable release) implements AutoCloseable {

		@Override
		public void close() {
			release.run();
		}
	}

	/**
	 * Takes a slot and the caller's budget, reads the scope and counts. [format] is null for CSV.
	 *
	 * @throws ApiException 429 when the instance is busy or the caller over budget
	 */
	public Plan plan(User viewer, TimeReportFilter filter, TimeReportService.GroupBy groupBy, ExportFormat format) {
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
			Query query = reports.detailedQuery(viewer, filter);
			boolean truncated = mongo.count(Query.of(query).limit(max + 1), WorkItem.class) > max;
			String name = "time-report-" + filter.from() + "-" + filter.to() + "."
					+ (format == null ? "csv" : format.extension());
			return new Plan(viewer, filter, groupBy, query, max, truncated, name, release);
		}
		catch (RuntimeException refused) {
			release.run();
			throw refused;
		}
	}

	/** The entries as CSV, a byte-order mark first so a spreadsheet reads the umlauts right. */
	public void writeCsv(Plan plan, ExportWords words, OutputStream target) throws IOException {
		Writer out = new BufferedWriter(new OutputStreamWriter(target, StandardCharsets.UTF_8));
		out.write('﻿');
		ExportText.csvRow(out, headers(words));
		long written = 0;
		try (Stream<WorkItem> stream = stream(plan)) {
			for (List<String> row : rows(stream, plan, words, true)) {
				ExportText.csvRow(out, row);
				if (++written % CHUNK == 0) {
					out.flush();
				}
			}
		}
		if (plan.truncated()) {
			ExportText.csvRow(out, List.of(words.t("export.report.truncated", plan.maxRows())));
		}
		out.flush();
		audited(plan, "csv", written);
	}

	/** The report as a document — totals, the groups, then the entries — in [format]. */
	public void writeDocument(Plan plan, ExportFormat format, ExportWords words, OutputStream out) {
		ExportDocumentRenderer renderer = renderers.get(format);
		if (renderer == null) {
			throw ApiException.badRequest("error.issue.exportFailed");
		}
		AtomicLong written = new AtomicLong();
		try (Stream<WorkItem> stream = stream(plan)) {
			Iterable<List<String>> entries = rows(stream, plan, words, format == ExportFormat.XLSX);
			Iterable<List<String>> counted = () -> {
				Iterator<List<String>> inner = entries.iterator();
				return new Iterator<>() {
					@Override
					public boolean hasNext() {
						return inner.hasNext();
					}

					@Override
					public List<String> next() {
						written.incrementAndGet();
						return inner.next();
					}
				};
			};
			renderer.render(document(plan, words, counted, format), out);
		}
		audited(plan, format.extension(), written.get());
	}

	private ExportDocument document(Plan plan, ExportWords words, Iterable<List<String>> entries, ExportFormat format) {
		boolean machine = format == ExportFormat.XLSX;
		TimeReportService.Summary summary = reports.summary(plan.viewer(), plan.filter(), plan.groupBy(), 0,
				SUMMARY_GROUPS);
		TimeReportService.Totals totals = summary.totals();
		List<ExportBlock> blocks = new ArrayList<>();
		List<ExportBlock.KeyValue> head = new ArrayList<>();
		head.add(new ExportBlock.KeyValue(words.t("export.report.total"), duration(totals.minutes(), machine)));
		head.add(new ExportBlock.KeyValue(words.t("export.report.billable"),
				duration(totals.billableMinutes(), machine)));
		head.add(new ExportBlock.KeyValue(words.t("export.report.entries"), String.valueOf(totals.entries())));
		if (totals.filedMinutes() != totals.minutes()) {
			head.add(new ExportBlock.KeyValue(words.t("export.report.filed"), duration(totals.filedMinutes(), machine)));
		}
		blocks.add(new ExportBlock.KeyValues(head));

		String groupName = words.t("export.report.group." + plan.groupBy().name());
		blocks.add(new ExportBlock.Section(words.t("export.report.section.summary", groupName)));
		List<List<String>> groups = new ArrayList<>();
		for (TimeReportService.Group group : summary.groups().getContent()) {
			groups.add(List.of(groupLabel(group, plan.groupBy(), words), duration(group.minutes(), machine),
					duration(group.billableMinutes(), machine), String.valueOf(group.entries())));
		}
		blocks.add(new ExportBlock.Table(List.of(groupName, words.t("export.report.column.duration"),
				words.t("export.report.billable"), words.t("export.report.entries")), groups,
				List.of(3f, 1f, 1f, 0.8f), Set.of(1, 2, 3)));
		if (summary.groups().getTotalElements() > groups.size()) {
			blocks.add(new ExportBlock.Note(words.t("export.report.moreGroups", groups.size(),
					summary.groups().getTotalElements())));
		}

		blocks.add(new ExportBlock.Section(words.t("export.report.section.entries")));
		blocks.add(new ExportBlock.LongTable(headers(words), entries,
				List.of(1f, 1.1f, 1.1f, 0.7f, 0.7f, 0.7f, 1.3f, 1.2f, 0.9f, 1.8f, 1f, 2.4f, 1f, 0.7f),
				Set.of(3, 4, 5)));
		if (plan.truncated()) {
			blocks.add(new ExportBlock.Note(words.t("export.report.truncated", plan.maxRows())));
		}
		String period = words.date(plan.filter().from()) + " – " + words.date(plan.filter().to());
		return new ExportDocument(words.t("export.report.eyebrow"), period, rounding(plan.filter(), words), blocks,
				organization(), logo(), clock.instant(), words);
	}

	private static List<String> headers(ExportWords words) {
		return List.of(words.t("export.report.column.date"), words.t("export.report.column.start"),
				words.t("export.report.column.end"), words.t("export.report.column.duration"),
				words.t("export.report.column.minutes"), words.t("export.report.column.filed"),
				words.t("export.report.column.person"), words.t("export.report.column.project"),
				words.t("export.report.column.issue"), words.t("export.report.column.issueTitle"),
				words.t("export.report.column.activity"), words.t("export.report.column.description"),
				words.t("export.report.column.tags"), words.t("export.report.column.billable"));
	}

	/**
	 * The entries as rows, read in chunks: each chunk's people, projects and issues are looked up
	 * together, then its rows handed out one by one. Walkable once, like the cursor under it.
	 */
	private Iterable<List<String>> rows(Stream<WorkItem> stream, Plan plan, ExportWords words, boolean machine) {
		Iterator<WorkItem> source = stream.iterator();
		return () -> new Iterator<>() {
			private final Deque<List<String>> buffer = new ArrayDeque<>();
			private long emitted;

			@Override
			public boolean hasNext() {
				if (buffer.isEmpty() && emitted < plan.maxRows() && source.hasNext()) {
					List<WorkItem> chunk = new ArrayList<>(CHUNK);
					while (chunk.size() < CHUNK && emitted + chunk.size() < plan.maxRows() && source.hasNext()) {
						chunk.add(source.next());
					}
					for (TimeReportService.EntryRow row : reports.rows(chunk, plan.filter())) {
						buffer.add(row(row, plan.filter(), words, machine));
					}
				}
				return !buffer.isEmpty();
			}

			@Override
			public List<String> next() {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}
				emitted++;
				return buffer.removeFirst();
			}
		};
	}

	private static List<String> row(TimeReportService.EntryRow row, TimeReportFilter filter, ExportWords words,
			boolean machine) {
		return List.of(
				machine ? String.valueOf(row.date()) : words.date(row.date()),
				row.startedAt() == null ? "" : LOCAL.format(row.startedAt().atZone(filter.zone())),
				row.endedAt() == null ? "" : LOCAL.format(row.endedAt().atZone(filter.zone())),
				duration(row.roundedMinutes(), machine),
				String.valueOf(row.roundedMinutes()),
				String.valueOf(row.minutes()),
				nz(row.userName()),
				row.projectKey() == null ? nz(row.projectName()) : row.projectKey(),
				nz(row.issueKey()),
				nz(row.issueTitle()),
				nz(row.activity()),
				nz(row.description()),
				String.join(", ", row.tags()),
				words.t(row.billable() ? "export.report.yes" : "export.report.no"));
	}

	private static String groupLabel(TimeReportService.Group group, TimeReportService.GroupBy groupBy,
			ExportWords words) {
		if (group.key() == null) {
			return words.t("export.report.none." + groupBy.name());
		}
		if (groupBy.isTime()) {
			return words.date(java.time.LocalDate.parse(group.key()));
		}
		String label = group.label() != null ? group.label() : group.key();
		return group.detail() != null && groupBy != TimeReportService.GroupBy.USER
				? group.detail() + " · " + label : label;
	}

	/** Hours and minutes for a person, decimal hours for a spreadsheet that adds them up. */
	private static String duration(long minutes, boolean machine) {
		if (machine) {
			return String.format(Locale.ROOT, "%.2f", minutes / 60.0);
		}
		return String.format(Locale.ROOT, "%d:%02d", minutes / 60, Math.abs(minutes % 60));
	}

	private static String rounding(TimeReportFilter filter, ExportWords words) {
		TimeTrackingSettings.Rounding rounding = filter.rounding();
		if (rounding == null || rounding.mode() == null || rounding.increment() <= 1
				|| rounding.mode() == com.ahmadre.hinata.common.TimePolicy.Rounding.NONE) {
			return "";
		}
		return words.t("export.report.rounding", words.t("export.report.rounding." + rounding.mode().name()),
				rounding.increment());
	}

	private Stream<WorkItem> stream(Plan plan) {
		return mongo.stream(Query.of(plan.query()).limit(plan.maxRows()).cursorBatchSize(CHUNK), WorkItem.class);
	}

	private void audited(Plan plan, String format, long rows) {
		audit.event(AuditAction.TIME_REPORT_EXPORTED).actor(plan.viewer())
				.meta("format", format)
				.meta("filter", hash(plan.filter()))
				.meta("rows", String.valueOf(rows))
				.meta("truncated", String.valueOf(plan.truncated()))
				.log();
	}

	/**
	 * A fingerprint of the filter: two exports with the same one can be told apart from two with
	 * different ones, without the audit log holding the names and words that were searched for.
	 */
	static String hash(TimeReportFilter filter) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(filter.toString().getBytes(StandardCharsets.UTF_8));
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

	/** The letterhead's mark, or none: a logo that cannot be read costs the letterhead, not the file. */
	private byte[] logo() {
		try {
			return brandLogo.raster().orElse(null);
		}
		catch (RuntimeException ex) {
			log.warn("The organization logo was left out of a time report: {}", ex.toString());
			return null;
		}
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}
}
