package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.export.ExportRateLimiter;
import com.ahmadre.hinata.issue.export.ExportText;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A person's own entries as a spreadsheet file (Art. 20 DSGVO — the data in a
 * structured, commonly used, machine-readable format).
 *
 * <p>Streamed, never assembled: the cursor hands over {@value #CHUNK} entries at a
 * time, each chunk resolves its project keys and issue ids in two reads, is written
 * and flushed, and the next one follows. A career of entries costs one chunk of
 * memory. Capped at {@value #MAX_ROWS} rows, which is decades of any real person's
 * record; a request that would pass it is told so in a header before the first byte.
 *
 * <p>What makes it safe to open is {@link ExportText#forSpreadsheet}: descriptions
 * and tags are free text, and a cell beginning with {@code =} is a formula to every
 * spreadsheet application. The file is the person's own data, but it is the kind of
 * file that gets forwarded.
 *
 * <p>Identifiers rather than titles for the placement: the project's key and the
 * issue's readable id say where the time was booked, and a title is somebody else's
 * text about a ticket the person may no longer be able to see.
 */
@Service
@RequiredArgsConstructor
public class TimeEntryCsvExport {

	static final int MAX_ROWS = 100_000;
	static final int CHUNK = 500;

	private static final List<String> HEADERS = List.of("date", "startedAt", "endedAt",
			"durationMinutes", "project", "issue", "activityType", "description", "tags", "billable",
			"source", "createdAt", "updatedAt", "id");

	/**
	 * How many files may be streaming at once, across everybody.
	 *
	 * <p>Each one holds a request thread and a database cursor for as long as the
	 * client takes to read it, and the per-person budget does not bound that: forty
	 * people pressing the button in the same minute are forty cursors. Four at a
	 * time keeps a burst from starving the instance, and the fifth person is told to
	 * try again rather than kept waiting on a connection.
	 */
	static final int MAX_RUNNING = 4;

	private final MongoTemplate mongo;
	private final ExportRateLimiter rateLimiter;
	private final Semaphore running = new Semaphore(MAX_RUNNING);

	/**
	 * What the controller needs before the body: whether the cap will cut the file.
	 * Holds one of the {@value #MAX_RUNNING} slots until it is closed.
	 */
	public record Plan(Query query, boolean truncated, String fileName, Runnable release)
			implements AutoCloseable {

		@Override
		public void close() {
			release.run();
		}
	}

	/**
	 * Checks the request, takes a slot and the budget, and counts — everything that
	 * can refuse, so nothing refuses after the response has started. The caller
	 * closes the plan when the file is written.
	 */
	public Plan plan(LocalDate from, LocalDate to, User user) {
		if (from != null && to != null && to.isBefore(from)) {
			throw ApiException.badRequest("error.time.rangeNotAscending");
		}
		// The values before the budget: a year the storage layer cannot carry is the
		// caller's mistake, and it must neither spend an export nor reach the driver.
		if (from != null || to != null) {
			TimeTrackingService.assertStorable(from != null ? from : to, to != null ? to : from,
					"error.time.rangeOutOfBounds");
		}
		// A slot before the budget: an instance busy with other people's files is not
		// the caller's doing, and must not cost them one of their own exports.
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
			return plan(from, to, user, release);
		}
		catch (RuntimeException refused) {
			release.run();
			throw refused;
		}
	}

	private Plan plan(LocalDate from, LocalDate to, User user, Runnable release) {
		rateLimiter.require(user.getId());
		Criteria criteria = Criteria.where("userId").is(user.getId());
		if (from != null && to != null) {
			criteria = criteria.and("date").gte(from).lte(to);
		}
		else if (from != null) {
			criteria = criteria.and("date").gte(from);
		}
		else if (to != null) {
			criteria = criteria.and("date").lte(to);
		}
		// The personal list's own order, which user_date_started serves as an index
		// walk — no blocking sort over a career of entries.
		Query query = Query.query(criteria).with(Sort.by(Sort.Order.desc("date"),
				Sort.Order.desc("startedAt"), Sort.Order.desc("_id")));
		boolean truncated = mongo.count(Query.of(query).limit(MAX_ROWS + 1), WorkItem.class) > MAX_ROWS;
		String name = "time-entries-" + (from == null ? "all" : from.toString())
				+ (to == null ? "" : "-" + to) + ".csv";
		return new Plan(query, truncated, name, release);
	}

	/** Writes the file. UTF-8 with a byte-order mark, so a spreadsheet reads umlauts. */
	public void write(Plan plan, OutputStream target) throws IOException {
		Writer out = new BufferedWriter(new OutputStreamWriter(target, StandardCharsets.UTF_8));
		out.write('﻿');
		writeRow(out, HEADERS);
		Query query = Query.of(plan.query()).limit(MAX_ROWS).cursorBatchSize(CHUNK);
		try (Stream<WorkItem> stream = mongo.stream(query, WorkItem.class)) {
			Iterator<WorkItem> items = stream.iterator();
			List<WorkItem> chunk = new ArrayList<>(CHUNK);
			while (items.hasNext()) {
				chunk.add(items.next());
				if (chunk.size() == CHUNK || !items.hasNext()) {
					writeChunk(out, chunk);
					out.flush();
					chunk.clear();
				}
			}
		}
		out.flush();
	}

	private void writeChunk(Writer out, List<WorkItem> chunk) throws IOException {
		Map<String, String> projectKeys = namesOf(chunk, WorkItem::getProjectId, Project.class, "key");
		Map<String, String> issueIds = namesOf(chunk, WorkItem::getIssueId, Issue.class, "readableId");
		for (WorkItem item : chunk) {
			writeRow(out, List.of(
					String.valueOf(item.getDate()),
					text(item.getStartedAt()),
					text(item.getEndedAt()),
					String.valueOf(item.getDurationMinutes()),
					lookup(projectKeys, item.getProjectId()),
					lookup(issueIds, item.getIssueId()),
					orEmpty(item.getActivityType()),
					orEmpty(item.getDescription()),
					String.join("; ", item.getTags()),
					String.valueOf(item.isBillable()),
					item.getSource().name(),
					text(item.getCreatedAt()),
					text(item.getUpdatedAt()),
					orEmpty(item.getId())));
		}
	}

	/** One field of the referenced documents, by id, for the ids this chunk names. */
	private Map<String, String> namesOf(List<WorkItem> chunk, Function<WorkItem, String> reference,
			Class<?> type, String field) {
		Set<String> ids = chunk.stream().map(reference).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (ids.isEmpty()) {
			return Map.of();
		}
		Query query = Query.query(Criteria.where("_id").in(ids));
		query.fields().include(field);
		return mongo.find(query, org.bson.Document.class, mongo.getCollectionName(type)).stream()
				.filter(document -> document.get(field) != null)
				.collect(Collectors.toMap(document -> String.valueOf(document.get("_id")),
						document -> String.valueOf(document.get(field)), (a, b) -> a));
	}

	private static void writeRow(Writer out, List<String> cells) throws IOException {
		ExportText.csvRow(out, cells);
	}

	/** The resolved name, or empty — an entry without a project or issue has no key to look up. */
	private static String lookup(Map<String, String> names, String id) {
		return id == null ? "" : orEmpty(names.get(id));
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}
}
