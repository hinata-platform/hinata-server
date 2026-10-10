package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.timetracking.TimeRounding;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.context.MessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Invoices and credit notes (HIN-96). See {@link Invoice} for what they are; this is how they are
 * written.
 *
 * <p>A draft is built from a filter — one project, a window of at most {@link #PERIOD_MAX_DAYS}
 * days, the billable entries nobody has billed yet, at most {@link #ENTRIES_MAX} — grouped into
 * lines by project, issue, person or day, and within a group by rate, so every line has one rate
 * and its amount is that rate times its minutes. The entries are valued exactly the way the
 * billing report values them (rate on the entry's day, minutes rounded first).
 *
 * <p>Issuing happens in an order chosen so the number sequence has no gaps:
 * <ol>
 * <li>claim the draft ({@code DRAFT → ISSUING}, one atomic write — a second caller is refused);</li>
 * <li>mark its entries as billed, but only those still billable, unbilled and in the period;</li>
 * <li>check that what is marked is exactly what the draft shows — otherwise everything is undone
 * and the caller told to refresh the draft;</li>
 * <li>only then take the next number and write it with {@code ISSUED}.</li>
 * </ol>
 * Two drafts naming the same entry cannot both be issued: the second one's marking finds the
 * entry already billed. An issue interrupted between 1 and 4 leaves {@code ISSUING} behind; it can
 * be issued again after {@link #STALE_CLAIM}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

	/** Longest service period one draft covers. */
	public static final int PERIOD_MAX_DAYS = 92;

	/** Most entries one draft is built from. */
	public static final int ENTRIES_MAX = 5_000;

	/** Largest page. */
	public static final int PAGE_MAX = 100;

	/** Longest free text: a recipient's name, address, VAT id, the notes. */
	public static final int TEXT_MAX = 500;

	/** Highest tax rate in basis points (100 %). */
	static final int TAX_MAX = 10_000;

	/** How long an interrupted issue keeps its claim before somebody may issue again. */
	static final Duration STALE_CLAIM = Duration.ofMinutes(10);

	private final MongoTemplate mongo;
	private final InvoiceRepository invoices;
	private final InvoiceNumbers numbers;
	private final BillingAccess access;
	private final BillingSettings settings;
	private final BillingRateService rates;
	private final BillingNames names;
	private final MessageSource messages;
	private final AuditService audit;
	private final Clock clock;

	/** What a client sends to draft an invoice. */
	public record DraftRequest(String projectId, LocalDate from, LocalDate to, Invoice.Grouping grouping,
			Invoice.Recipient recipient, String notes, Integer taxBasisPoints) {
	}

	/** What a client may change on a draft. Absent fields stay. */
	public record Change(Invoice.Recipient recipient, String notes, Integer taxBasisPoints,
			List<String> removeLineIds, Map<String, String> lineDescriptions) {
	}

	/** One record in a list. */
	public record Summary(String id, Invoice.Kind kind, String number, Invoice.Status status, String projectId,
			String projectKey, String projectName, LocalDate periodFrom, LocalDate periodTo, String currency,
			String recipientName, Invoice.Totals totals, Instant issuedAt, Instant createdAt, String creditNoteId,
			String creditedInvoiceId) {
	}

	/** One record in full, without its lines (those are paged). */
	public record Detail(Summary summary, Invoice.Grouping grouping, Invoice.Recipient recipient, String notes,
			int taxBasisPoints, String language, int lineCount, int unratedLines, boolean editable,
			String issuedBy, String creditedInvoiceNumber, String creditNoteNumber) {
	}

	/** One line as a client reads it: the entries are a count, not a list. */
	public record LineView(String id, String description, int entries, long minutes, long rateCents,
			long amountCents, boolean unrated) {
	}

	// --- reading ------------------------------------------------------------

	public Page<Summary> page(User viewer, Invoice.Status status, String projectId, int page, int size) {
		BillingAccess.Reach reach = access.require(viewer);
		List<Criteria> all = new ArrayList<>();
		if (!reach.admin()) {
			if (projectId != null && !reach.leads(projectId)) {
				throw ApiException.forbidden("error.billing.notLead");
			}
			all.add(Criteria.where("projectId").in(projectId != null ? List.of(projectId) : reach.ledProjects()));
		}
		else if (projectId != null) {
			all.add(Criteria.where("projectId").is(projectId));
		}
		if (status != null) {
			all.add(Criteria.where("status").is(status));
		}
		Query query = Query.query(all.isEmpty() ? new Criteria() : new Criteria().andOperator(all));
		query.fields().exclude("lines");
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, 10_000), Math.clamp(size, 1, PAGE_MAX),
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")));
		List<Invoice> found = mongo.find(Query.of(query).with(pageable), Invoice.class);
		return PageableExecutionUtils.getPage(summaries(viewer, found), pageable,
				() -> mongo.count(query, Invoice.class));
	}

	public Detail get(User viewer, String id) {
		Invoice invoice = readable(viewer, id);
		return detail(viewer, invoice);
	}

	public Page<LineView> lines(User viewer, String id, int page, int size) {
		Invoice invoice = readable(viewer, id);
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, 10_000), Math.clamp(size, 1, PAGE_MAX));
		List<Invoice.Line> all = invoice.getLines();
		int from = (int) Math.min(pageable.getOffset(), all.size());
		int to = Math.min(from + pageable.getPageSize(), all.size());
		List<LineView> views = all.subList(from, to).stream()
				.map(line -> new LineView(line.id(), line.description(),
						line.entryIds() == null ? 0 : line.entryIds().size(), line.minutes(), line.rateCents(),
						line.amountCents(), line.unrated()))
				.toList();
		return new PageImpl<>(views, pageable, all.size());
	}

	/** The number of another record, for a credit note's reference. */
	String numberOf(String id) {
		return invoices.findById(id).map(Invoice::getNumber).orElse(null);
	}

	/** The record for an export: readable by the viewer, lines included. */
	Invoice forExport(User viewer, String id) {
		return readable(viewer, id);
	}

	// --- drafts -------------------------------------------------------------

	public Detail createDraft(User viewer, DraftRequest request, Locale locale) {
		BillingAccess.Reach reach = access.require(viewer);
		if (request.projectId() == null || request.projectId().isBlank()) {
			throw ApiException.badRequest("error.billing.invoice.project");
		}
		access.requireLead(reach, request.projectId());
		checkPeriod(request.from(), request.to());
		Invoice.Grouping grouping = request.grouping() == null ? Invoice.Grouping.ISSUE : request.grouping();
		if (grouping == Invoice.Grouping.USER && !reach.admin() && !settings.leadsSeeMemberEntries()) {
			throw ApiException.forbidden("error.billing.report.people");
		}
		Instant now = clock.instant();
		Invoice invoice = Invoice.builder()
				.projectId(request.projectId()).periodFrom(request.from()).periodTo(request.to())
				.grouping(grouping).currency(settings.currency()).language(locale.toLanguageTag())
				.recipient(recipient(request.recipient())).notes(text(request.notes()))
				.taxBasisPoints(tax(request.taxBasisPoints()))
				.createdBy(viewer.getId()).createdAt(now).updatedBy(viewer.getId()).updatedAt(now)
				.build();
		build(viewer, invoice);
		Invoice saved = invoices.save(invoice);
		audit.event(AuditAction.INVOICE_CREATED).actor(viewer).target(saved.getId(), saved.getProjectId())
				.meta("project", saved.getProjectId())
				.meta("from", String.valueOf(saved.getPeriodFrom()))
				.meta("to", String.valueOf(saved.getPeriodTo()))
				.meta("lines", String.valueOf(saved.getLines().size()))
				.log();
		return detail(viewer, saved);
	}

	public Detail update(User viewer, String id, Change change) {
		Invoice invoice = draft(viewer, id);
		if (change.recipient() != null) {
			invoice.setRecipient(recipient(change.recipient()));
		}
		if (change.notes() != null) {
			invoice.setNotes(text(change.notes()));
		}
		if (change.taxBasisPoints() != null) {
			invoice.setTaxBasisPoints(tax(change.taxBasisPoints()));
		}
		List<Invoice.Line> lines = new ArrayList<>(invoice.getLines());
		if (change.removeLineIds() != null && !change.removeLineIds().isEmpty()) {
			Set<String> removed = Set.copyOf(change.removeLineIds());
			lines.removeIf(line -> removed.contains(line.id()));
		}
		if (change.lineDescriptions() != null) {
			if (change.lineDescriptions().size() > ENTRIES_MAX) {
				throw ApiException.badRequest("error.billing.invoice.text", TEXT_MAX);
			}
			List<Invoice.Line> described = new ArrayList<>(lines.size());
			for (Invoice.Line line : lines) {
				String description = change.lineDescriptions().get(line.id());
				described.add(description == null ? line
						: new Invoice.Line(line.id(), requiredText(description), line.groupKey(), line.entryIds(),
								line.minutes(), line.rateCents(), line.amountCents(), line.unrated()));
			}
			lines = described;
		}
		invoice.setLines(lines);
		invoice.setTotals(totals(lines, invoice.getTaxBasisPoints()));
		stamp(invoice, viewer);
		return detail(viewer, save(invoice));
	}

	/** Rebuilds a draft's lines from its filter: what changed among the entries since, included. */
	public Detail refresh(User viewer, String id) {
		Invoice invoice = draft(viewer, id);
		build(viewer, invoice);
		stamp(invoice, viewer);
		return detail(viewer, save(invoice));
	}

	public void delete(User viewer, String id) {
		Invoice invoice = draft(viewer, id);
		invoices.delete(invoice);
		audit.event(AuditAction.INVOICE_DELETED).actor(viewer).target(invoice.getId(), invoice.getProjectId())
				.meta("project", invoice.getProjectId())
				.log();
	}

	// --- issuing ------------------------------------------------------------

	public Detail issue(User viewer, String id) {
		Invoice invoice = draftOrStaleClaim(viewer, id);
		if (invoice.getLines().isEmpty()) {
			throw ApiException.badRequest("error.billing.invoice.empty");
		}
		if (invoice.getLines().stream().anyMatch(Invoice.Line::unrated)) {
			throw ApiException.conflict("error.billing.invoice.unrated");
		}
		Instant now = clock.instant();
		Invoice claimed = claim(invoice, now);
		boolean marked = false;
		try {
			List<String> entryIds = claimed.entryIds();
			mark(claimed, entryIds);
			marked = true;
			if (!matches(claimed)) {
				throw ApiException.conflict("error.billing.invoice.stale");
			}
			int year = LocalDate.ofInstant(now, settings.zone()).getYear();
			String number = numbers.next(settings.invoicePrefix(), year);
			Invoice issued = finish(claimed.getId(), number, viewer, now);
			audit.event(AuditAction.INVOICE_ISSUED).actor(viewer).target(issued.getId(), number)
					.meta("project", issued.getProjectId())
					.meta("number", number)
					.meta("entries", String.valueOf(entryIds.size()))
					.meta("netCents", String.valueOf(issued.getTotals().netCents()))
					.log();
			return detail(viewer, issued);
		}
		catch (RuntimeException refused) {
			undo(claimed.getId(), marked);
			throw refused;
		}
	}

	/** Reverses an issued invoice with a credit note and releases its entries. */
	public Detail creditNote(User viewer, String id, String reason) {
		BillingAccess.Reach reach = access.require(viewer);
		Invoice original = invoices.findById(id).orElseThrow(() -> ApiException.notFound("invoice"));
		hideUnless(reach, original);
		access.requireLead(reach, original.getProjectId());
		if (original.getKind() != Invoice.Kind.INVOICE || original.getStatus() != Invoice.Status.ISSUED) {
			throw ApiException.conflict("error.billing.invoice.notCreditable");
		}
		String pending = "pending:" + UUID.randomUUID();
		Invoice held = mongo.findAndModify(Query.query(Criteria.where("_id").is(original.getId())
						.and("status").is(Invoice.Status.ISSUED).and("creditNoteId").is(null)),
				new Update().set("creditNoteId", pending), FindAndModifyOptions.options().returnNew(true),
				Invoice.class);
		if (held == null) {
			throw ApiException.conflict("error.billing.invoice.alreadyCredited");
		}
		Instant now = clock.instant();
		try {
			List<Invoice.Line> negated = new ArrayList<>(held.getLines().size());
			for (Invoice.Line line : held.getLines()) {
				negated.add(new Invoice.Line(UUID.randomUUID().toString(), line.description(), line.groupKey(),
						List.of(), -line.minutes(), line.rateCents(), -line.amountCents(), false));
			}
			int year = LocalDate.ofInstant(now, settings.zone()).getYear();
			String number = numbers.next(settings.invoicePrefix(), year);
			Invoice credit = invoices.insert(Invoice.builder()
					.kind(Invoice.Kind.CREDIT_NOTE).number(number).status(Invoice.Status.ISSUED)
					.projectId(held.getProjectId()).periodFrom(held.getPeriodFrom()).periodTo(held.getPeriodTo())
					.grouping(held.getGrouping()).currency(held.getCurrency()).language(held.getLanguage())
					.recipient(held.getRecipient()).taxBasisPoints(held.getTaxBasisPoints())
					.notes(text(reason)).lines(negated).totals(totals(negated, held.getTaxBasisPoints()))
					.creditedInvoiceId(held.getId()).issuedAt(now).issuedBy(viewer.getId())
					.createdBy(viewer.getId()).createdAt(now).updatedBy(viewer.getId()).updatedAt(now)
					.build());
			mongo.updateFirst(Query.query(Criteria.where("_id").is(held.getId())),
					new Update().set("creditNoteId", credit.getId()), Invoice.class);
			// The entries are free again: to be corrected (Art. 16 DSGVO) and billed anew.
			long released = mongo.updateMulti(Query.query(Criteria.where("invoiceId").is(held.getId())),
					new Update().unset("invoiceId"), WorkItem.class).getModifiedCount();
			audit.event(AuditAction.INVOICE_CREDITED).actor(viewer).target(held.getId(), held.getNumber())
					.meta("project", held.getProjectId())
					.meta("number", held.getNumber())
					.meta("creditNote", number)
					.meta("released", String.valueOf(released))
					.log();
			return detail(viewer, credit);
		}
		catch (RuntimeException failed) {
			mongo.updateFirst(Query.query(Criteria.where("_id").is(held.getId()).and("creditNoteId").is(pending)),
					new Update().unset("creditNoteId"), Invoice.class);
			throw failed;
		}
	}

	// --- issuing, step by step ---------------------------------------------

	/** Step 1: the draft becomes ISSUING in one write, or somebody else got there first. */
	private Invoice claim(Invoice invoice, Instant now) {
		Criteria claimable = new Criteria().orOperator(Criteria.where("status").is(Invoice.Status.DRAFT),
				Criteria.where("status").is(Invoice.Status.ISSUING).and("updatedAt").lt(now.minus(STALE_CLAIM)));
		Invoice claimed = mongo.findAndModify(
				Query.query(new Criteria().andOperator(Criteria.where("_id").is(invoice.getId()), claimable)),
				new Update().set("status", Invoice.Status.ISSUING).set("updatedAt", now).inc("version", 1),
				FindAndModifyOptions.options().returnNew(true), Invoice.class);
		if (claimed == null) {
			throw ApiException.conflict("error.billing.invoice.notDraft");
		}
		return claimed;
	}

	/**
	 * Step 2: marks the draft's entries as billed — only those still billable, unbilled, of the
	 * project and inside the period. Entries a stale earlier attempt of the same invoice marked
	 * count as this invoice's.
	 */
	private void mark(Invoice invoice, List<String> entryIds) {
		for (List<String> chunk : chunks(entryIds)) {
			mongo.updateMulti(Query.query(Criteria.where("_id").in(chunk).and("invoiceId").exists(false)
							.and("billable").is(true).and("projectId").is(invoice.getProjectId())
							.and("date").gte(invoice.getPeriodFrom()).lte(invoice.getPeriodTo())),
					new Update().set("invoiceId", invoice.getId()), WorkItem.class);
		}
	}

	/**
	 * Step 3: whether the marked entries are exactly the draft's, with the minutes it shows. An
	 * entry deleted, made non-billable, moved, edited or billed elsewhere since the draft was built
	 * makes the draft stale.
	 */
	private boolean matches(Invoice invoice) {
		Map<String, Long> minutesById = new HashMap<>();
		Query marked = Query.query(Criteria.where("invoiceId").is(invoice.getId()));
		marked.fields().include("durationMinutes");
		for (Document entry : mongo.query(WorkItem.class).as(Document.class).matching(marked).all()) {
			int filed = entry.get("durationMinutes") instanceof Number number ? number.intValue() : 0;
			minutesById.put(String.valueOf(entry.get("_id")), (long) TimeRounding.round(filed, settings.rounding()));
		}
		int expected = 0;
		for (Invoice.Line line : invoice.getLines()) {
			long minutes = 0;
			for (String entryId : line.entryIds()) {
				Long found = minutesById.get(entryId);
				if (found == null) {
					return false;
				}
				minutes += found;
				expected++;
			}
			if (minutes != line.minutes()) {
				return false;
			}
		}
		return expected == minutesById.size();
	}

	/** Step 4: the number and ISSUED, written by the one caller holding the claim. */
	private Invoice finish(String id, String number, User viewer, Instant now) {
		Update issued = new Update().set("status", Invoice.Status.ISSUED).set("number", number)
				.set("issuedAt", now).set("issuedBy", viewer.getId()).set("updatedAt", now)
				.set("updatedBy", viewer.getId()).inc("version", 1);
		// Retried rather than abandoned: the number is taken, and an invoice left without it
		// would be the gap the sequence exists to prevent.
		for (int attempt = 1; ; attempt++) {
			try {
				Invoice done = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)
								.and("status").is(Invoice.Status.ISSUING)), issued,
						FindAndModifyOptions.options().returnNew(true), Invoice.class);
				if (done == null) {
					throw new IllegalStateException("the claim on invoice " + id + " was lost");
				}
				return done;
			}
			catch (RuntimeException failed) {
				if (attempt >= 3) {
					log.error("Invoice {} could not be given its number {}; the sequence has a gap", id, number,
							failed);
					throw failed;
				}
			}
		}
	}

	/** Takes back steps 1 and 2 after a refusal. */
	private void undo(String id, boolean marked) {
		try {
			if (marked) {
				mongo.updateMulti(Query.query(Criteria.where("invoiceId").is(id)), new Update().unset("invoiceId"),
						WorkItem.class);
			}
			mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("status").is(Invoice.Status.ISSUING)),
					new Update().set("status", Invoice.Status.DRAFT).inc("version", 1), Invoice.class);
		}
		catch (RuntimeException failed) {
			log.warn("Could not take back the claim on invoice {}; it can be issued again in {}", id, STALE_CLAIM,
					failed);
		}
	}

	// --- building lines -----------------------------------------------------

	/** (Re)builds a draft's lines and totals from its filter. */
	private void build(User viewer, Invoice invoice) {
		Query query = Query.query(Criteria.where("projectId").is(invoice.getProjectId())
						.and("date").gte(invoice.getPeriodFrom()).lte(invoice.getPeriodTo())
						.and("billable").is(true).and("invoiceId").exists(false))
				.with(Sort.by(Sort.Order.asc("date"), Sort.Order.asc("_id")))
				.limit(ENTRIES_MAX + 1);
		query.fields().include("userId", "projectId", "issueId", "date", "durationMinutes");
		List<Document> entries = mongo.query(WorkItem.class).as(Document.class).matching(query).all();
		if (entries.size() > ENTRIES_MAX) {
			throw ApiException.badRequest("error.billing.invoice.tooManyEntries", ENTRIES_MAX);
		}
		if (entries.isEmpty()) {
			throw ApiException.badRequest("error.billing.invoice.empty");
		}
		RateResolver revenue = RateResolver.of(rates.all(BillingRate.Kind.BILLABLE));
		Map<String, List<String>> teams = names.teamsByPerson();
		record Key(String group, Long rateCents) {
		}
		Map<Key, List<String>> idsByKey = new LinkedHashMap<>();
		Map<Key, Long> minutesByKey = new HashMap<>();
		for (Document entry : entries) {
			LocalDate day = BillingReportService.day(entry.get("date"));
			String userId = entry.getString("userId");
			String issueId = entry.getString("issueId");
			int filed = entry.get("durationMinutes") instanceof Number number ? number.intValue() : 0;
			int minutes = TimeRounding.round(filed, settings.rounding());
			BillingRate rate = revenue.resolve(new RateResolver.Facts(userId, invoice.getProjectId(), issueId, day,
					userId == null ? List.of() : teams.getOrDefault(userId, List.of())));
			String group = switch (invoice.getGrouping()) {
				case PROJECT -> invoice.getProjectId();
				case ISSUE -> issueId;
				case USER -> userId;
				case DAY -> day.toString();
			};
			Key key = new Key(group, rate == null ? null : rate.getAmountCents());
			idsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(String.valueOf(entry.get("_id")));
			minutesByKey.merge(key, (long) minutes, Long::sum);
		}
		Map<String, String> descriptions = descriptions(viewer, invoice,
				idsByKey.keySet().stream().map(Key::group).toList());
		List<Invoice.Line> lines = new ArrayList<>(idsByKey.size());
		idsByKey.forEach((key, ids) -> {
			long minutes = minutesByKey.getOrDefault(key, 0L);
			boolean unrated = key.rateCents() == null;
			long rate = unrated ? 0 : key.rateCents();
			lines.add(new Invoice.Line(UUID.randomUUID().toString(),
					descriptions.getOrDefault(Objects.toString(key.group(), ""), ""), key.group(), List.copyOf(ids),
					minutes, rate, unrated ? 0 : Money.amount(rate, minutes), unrated));
		});
		Collator collator = Collator.getInstance(Locale.forLanguageTag(invoice.getLanguage()));
		collator.setStrength(Collator.SECONDARY);
		lines.sort(invoice.getGrouping() == Invoice.Grouping.DAY
				? Comparator.comparing((Invoice.Line line) -> Objects.toString(line.groupKey(), ""))
						.thenComparing(Invoice.Line::rateCents)
				: Comparator.comparing(Invoice.Line::description, collator).thenComparing(Invoice.Line::rateCents));
		invoice.setLines(lines);
		invoice.setTotals(totals(lines, invoice.getTaxBasisPoints()));
	}

	/**
	 * What each line is called, in the invoice's language. A project's name and an issue's title
	 * only where the writer is in the project (HIN-129); the key and the readable id always.
	 */
	private Map<String, String> descriptions(User viewer, Invoice invoice, List<String> groups) {
		Locale locale = Locale.forLanguageTag(invoice.getLanguage());
		Map<String, String> out = new HashMap<>();
		List<String> keys = groups.stream().filter(Objects::nonNull).toList();
		boolean readsProject = !names.readable(viewer.getId(), List.of(invoice.getProjectId())).isEmpty();
		switch (invoice.getGrouping()) {
			case PROJECT -> {
				BillingNames.Name project = names.projects(List.of(invoice.getProjectId())).get(invoice.getProjectId());
				out.put(Objects.toString(invoice.getProjectId(), ""), project == null ? ""
						: readsProject ? join(project.detail(), project.label()) : project.detail());
			}
			case ISSUE -> {
				Map<String, BillingNames.Name> issues = names.issues(keys);
				for (String key : keys) {
					BillingNames.Name issue = issues.get(key);
					out.put(key, issue == null ? messages.getMessage("billing.invoice.line.issueGone", null, locale)
							: readsProject ? join(issue.detail(), issue.label()) : issue.detail());
				}
				out.put("", messages.getMessage("billing.invoice.line.noIssue", null, locale));
			}
			case USER -> {
				Map<String, BillingNames.Name> users = names.users(keys);
				for (String key : keys) {
					BillingNames.Name user = users.get(key);
					out.put(key, user == null ? messages.getMessage("billing.invoice.line.personGone", null, locale)
							: user.label());
				}
				out.put("", messages.getMessage("billing.invoice.line.personGone", null, locale));
			}
			case DAY -> {
				DateTimeFormatter format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
				for (String key : keys) {
					out.put(key, format.format(LocalDate.parse(key)));
				}
			}
		}
		return out;
	}

	private static String join(String first, String second) {
		if (second == null || second.isBlank()) {
			return Objects.toString(first, "");
		}
		return first == null || first.isBlank() ? second : first + " · " + second;
	}

	static Invoice.Totals totals(List<Invoice.Line> lines, int taxBasisPoints) {
		long minutes = 0;
		long net = 0;
		for (Invoice.Line line : lines) {
			minutes += line.minutes();
			net += line.amountCents();
		}
		long tax = Money.tax(net, taxBasisPoints);
		return new Invoice.Totals(minutes, net, tax, net + tax);
	}

	// --- rules --------------------------------------------------------------

	private Invoice readable(User viewer, String id) {
		BillingAccess.Reach reach = access.require(viewer);
		Invoice invoice = invoices.findById(id).orElseThrow(() -> ApiException.notFound("invoice"));
		hideUnless(reach, invoice);
		return invoice;
	}

	/** A draft the viewer may write. */
	private Invoice draft(User viewer, String id) {
		Invoice invoice = readable(viewer, id);
		access.requireLead(access.of(viewer), invoice.getProjectId());
		if (invoice.getStatus() != Invoice.Status.DRAFT) {
			throw ApiException.conflict("error.billing.invoice.notDraft");
		}
		return invoice;
	}

	private Invoice draftOrStaleClaim(User viewer, String id) {
		Invoice invoice = readable(viewer, id);
		access.requireLead(access.of(viewer), invoice.getProjectId());
		boolean stale = invoice.getStatus() == Invoice.Status.ISSUING && invoice.getUpdatedAt() != null
				&& invoice.getUpdatedAt().isBefore(clock.instant().minus(STALE_CLAIM));
		if (invoice.getStatus() != Invoice.Status.DRAFT && !stale) {
			throw ApiException.conflict("error.billing.invoice.notDraft");
		}
		return invoice;
	}

	/** An invoice of a project the viewer does not bill is one that does not exist for them. */
	private static void hideUnless(BillingAccess.Reach reach, Invoice invoice) {
		if (!reach.leads(invoice.getProjectId())) {
			throw ApiException.notFound("invoice");
		}
	}

	private static void checkPeriod(LocalDate from, LocalDate to) {
		if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= PERIOD_MAX_DAYS) {
			throw ApiException.badRequest("error.billing.invoice.period", PERIOD_MAX_DAYS);
		}
	}

	private static Invoice.Recipient recipient(Invoice.Recipient recipient) {
		if (recipient == null) {
			return null;
		}
		return new Invoice.Recipient(text(recipient.name()), text(recipient.address()), text(recipient.vatId()));
	}

	private static String text(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.strip();
		if (trimmed.length() > TEXT_MAX) {
			throw ApiException.badRequest("error.billing.invoice.text", TEXT_MAX);
		}
		return trimmed;
	}

	private static String requiredText(String value) {
		String text = text(value);
		if (text == null) {
			throw ApiException.badRequest("error.billing.invoice.text", TEXT_MAX);
		}
		return text;
	}

	private static int tax(Integer basisPoints) {
		if (basisPoints == null) {
			return 0;
		}
		if (basisPoints < 0 || basisPoints > TAX_MAX) {
			throw ApiException.badRequest("error.billing.invoice.tax");
		}
		return basisPoints;
	}

	private Invoice save(Invoice invoice) {
		try {
			return invoices.save(invoice);
		}
		catch (OptimisticLockingFailureException raced) {
			throw ApiException.conflict("error.billing.invoice.changed");
		}
	}

	private void stamp(Invoice invoice, User viewer) {
		invoice.setUpdatedBy(viewer.getId());
		invoice.setUpdatedAt(clock.instant());
	}

	private static List<List<String>> chunks(List<String> ids) {
		List<List<String>> chunks = new ArrayList<>();
		for (int i = 0; i < ids.size(); i += 1_000) {
			chunks.add(ids.subList(i, Math.min(i + 1_000, ids.size())));
		}
		return chunks;
	}

	// --- views --------------------------------------------------------------

	private List<Summary> summaries(User viewer, List<Invoice> found) {
		List<String> projectIds = found.stream().map(Invoice::getProjectId).filter(Objects::nonNull).toList();
		Map<String, BillingNames.Name> projects = names.projects(projectIds);
		Set<String> readable = new HashSet<>(names.readable(viewer.getId(), projectIds));
		List<Summary> out = new ArrayList<>(found.size());
		for (Invoice invoice : found) {
			out.add(summary(invoice, projects.get(invoice.getProjectId()), readable.contains(invoice.getProjectId())));
		}
		return out;
	}

	private static Summary summary(Invoice invoice, BillingNames.Name project, boolean readsProject) {
		return new Summary(invoice.getId(), invoice.getKind(), invoice.getNumber(), invoice.getStatus(),
				invoice.getProjectId(), project == null ? null : project.detail(),
				project == null || !readsProject ? null : project.label(), invoice.getPeriodFrom(),
				invoice.getPeriodTo(), invoice.getCurrency(),
				invoice.getRecipient() == null ? null : invoice.getRecipient().name(), invoice.getTotals(),
				invoice.getIssuedAt(), invoice.getCreatedAt(),
				invoice.getCreditNoteId() != null && invoice.getCreditNoteId().startsWith("pending:") ? null
						: invoice.getCreditNoteId(),
				invoice.getCreditedInvoiceId());
	}

	private Detail detail(User viewer, Invoice invoice) {
		Summary summary = summaries(viewer, List.of(invoice)).getFirst();
		String creditedNumber = invoice.getCreditedInvoiceId() == null ? null
				: invoices.findById(invoice.getCreditedInvoiceId()).map(Invoice::getNumber).orElse(null);
		String creditNoteNumber = summary.creditNoteId() == null ? null
				: invoices.findById(summary.creditNoteId()).map(Invoice::getNumber).orElse(null);
		int unrated = (int) invoice.getLines().stream().filter(Invoice.Line::unrated).count();
		return new Detail(summary, invoice.getGrouping(), invoice.getRecipient(), invoice.getNotes(),
				invoice.getTaxBasisPoints(), invoice.getLanguage(), invoice.getLines().size(), unrated,
				invoice.getStatus() == Invoice.Status.DRAFT, invoice.getIssuedBy(), creditedNumber, creditNoteNumber);
	}
}
