package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.deletion.DeletionService;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.timetracking.WorkItemRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Billing (HIN-96) against a real database: the switch, who may price what, the reports, and an
 * invoice from draft through issue to credit note — with the gapless sequence under concurrency,
 * billed entries refusing every write, and a project with issued invoices refusing deletion.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Import(BillingIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class BillingIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
	static final LocalDate FROM = LocalDate.of(2026, 9, 1);
	static final LocalDate TO = LocalDate.of(2026, 9, 30);

	@TestConfiguration
	static class FrozenClock {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private BillingSettings billing;
	@Autowired
	private BillingGate gate;
	@Autowired
	private FeatureFlags flags;
	@Autowired
	private BillingRateService rates;
	@Autowired
	private BillingReportService reports;
	@Autowired
	private InvoiceService invoices;
	@Autowired
	private InvoiceExport exports;
	@Autowired
	private DeletionService deletion;
	@Autowired
	private MessageSource messages;

	private User admin;
	private User lead;
	private User member;
	private Project project;
	private Project other;

	@BeforeEach
	void seed() {
		for (String collection : List.of("projects", "users", "work_items", "billing_rates", "invoices",
				"invoice_sequences", "audit_log", "server_settings", "teams")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		policy(true);
		admin = user("admin", Role.ORG_ADMIN);
		lead = user("lead", Role.MEMBER);
		member = user("member", Role.MEMBER);
		project = project("HIN", lead);
		other = project("OTH", admin);
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.roles(role == Role.MEMBER ? Set.of(Role.MEMBER) : Set.of(Role.MEMBER, role)).active(true).timezone("UTC").locale("en").build());
	}

	private Project project(String key, User leader) {
		return projects.save(Project.builder().key(key).name(key + " project").leadId(leader.getId())
				.leadIds(new ArrayList<>(List.of(leader.getId())))
				.memberIds(new ArrayList<>(List.of(leader.getId(), member.getId()))).build());
	}

	private void policy(boolean billingOn) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setBillingEnabled(billingOn);
		current.setTimeTracking(block);
		settings.save(current);
	}

	private WorkItem entry(User owner, Project in, LocalDate day, int minutes, boolean billable) {
		return timeTracking.create(new TimeTrackingService.NewEntry(in.getId(), null, minutes, day, null,
				"work", null, null, List.of(), billable), WorkItem.Source.APP, owner);
	}

	private BillingRateService.RateView rate(User by, BillingRate.Kind kind, BillingRate.Scope scope, String scopeId,
			String secondaryId, long cents, LocalDate from) {
		return rates.create(by, new BillingRateService.Draft(kind, scope, scopeId, secondaryId, cents, from, null));
	}

	private static HttpStatus statusOf(Throwable thrown) {
		return ((ApiException) thrown).getStatus();
	}

	// --- the switch --------------------------------------------------------------------------------

	@Test
	void billingOffMeansNoRoutesAndNoFlag() {
		policy(false);

		assertThat(billing.enabled()).isFalse();
		assertThat(flags.effective()).containsEntry(BillingSettings.FLAG, false);
		assertThatThrownBy(() -> gate.preHandle(null, null, null))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.NOT_FOUND));

		policy(true);
		assertThat(flags.effective()).containsEntry(BillingSettings.FLAG, true);
		assertThat(gate.preHandle(null, null, null)).isTrue();
	}

	// --- rates ------------------------------------------------------------------------------------

	@Test
	void aLeadPricesTheirProjectAndNothingElse() {
		rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT, project.getId(), null, 9_000, FROM);
		rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT_MEMBER, project.getId(), member.getId(),
				9_500, FROM);

		assertThatThrownBy(() -> rate(lead, BillingRate.Kind.COST, BillingRate.Scope.USER, member.getId(), null,
				5_000, FROM)).satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT, other.getId(),
				null, 5_000, FROM)).satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.DEFAULT, null, null,
				5_000, FROM)).satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> rates.page(member, null, null, null, null, null, null, 0, 20))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));

		rate(admin, BillingRate.Kind.COST, BillingRate.Scope.USER, member.getId(), null, 5_000, FROM);
		assertThat(rates.page(lead, null, null, null, null, null, null, 0, 20).getContent())
				.extracting(BillingRateService.RateView::kind).containsOnly(BillingRate.Kind.BILLABLE).hasSize(2);
		assertThatThrownBy(() -> rates.page(lead, BillingRate.Kind.COST, null, null, null, null, null, 0, 20))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThat(rates.page(admin, null, null, null, null, null, null, 0, 20).getTotalElements()).isEqualTo(3);
		// The amount of a cost rate never reaches the log.
		AuditLog costRecord = mongo.findOne(Query.query(Criteria.where("action").is(AuditAction.BILLING_RATE_CREATED)
				.and("metadata.kind").is("COST")), AuditLog.class);
		assertThat(costRecord).isNotNull();
		assertThat(costRecord.getMetadata()).doesNotContainKey("amountCents");
	}

	@Test
	void aChangeFromADateClosesTheRunningRateAndOverlapsAreRefused() {
		BillingRateService.RateView first = rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT,
				project.getId(), null, 8_000, FROM);
		rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT, project.getId(), null, 9_500,
				LocalDate.of(2026, 9, 15));

		List<BillingRateService.RateView> timeline = rates.timeline(lead, BillingRate.Kind.BILLABLE,
				BillingRate.Scope.PROJECT, project.getId(), null);
		assertThat(timeline).hasSize(2);
		assertThat(timeline.get(1).id()).isEqualTo(first.id());
		assertThat(timeline.get(1).validTo()).isEqualTo(LocalDate.of(2026, 9, 14));

		assertThatThrownBy(() -> rates.create(lead, new BillingRateService.Draft(BillingRate.Kind.BILLABLE,
				BillingRate.Scope.PROJECT, project.getId(), null, 7_000L, LocalDate.of(2026, 9, 10),
				LocalDate.of(2026, 9, 20)))).satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));
	}

	// --- reports ----------------------------------------------------------------------------------

	@Test
	void revenueComesFromBillableEntriesCostFromAllAndTheRateFromEachDay() {
		rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT, project.getId(), null, 6_000, FROM);
		rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT, project.getId(), null, 12_000,
				LocalDate.of(2026, 9, 15));
		rate(admin, BillingRate.Kind.COST, BillingRate.Scope.USER, member.getId(), null, 3_000, FROM);
		entry(member, project, LocalDate.of(2026, 9, 10), 60, true);  // 60.00 revenue, 30.00 cost
		entry(member, project, LocalDate.of(2026, 9, 20), 60, true);  // 120.00 revenue, 30.00 cost
		entry(member, project, LocalDate.of(2026, 9, 21), 120, false); // no revenue, 60.00 cost

		BillingReportService.Report billingReport = reports.report(lead, new BillingReportService.ReportQuery(
				BillingReportService.Kind.BILLING, FROM, TO, null, BillingReportService.GroupBy.PROJECT), 0, 20);
		assertThat(billingReport.totals().revenueCents()).isEqualTo(18_000);
		assertThat(billingReport.totals().billableMinutes()).isEqualTo(120);
		assertThat(billingReport.totals().costCents()).isNull();
		assertThat(billingReport.costs()).isFalse();

		BillingReportService.Report profit = reports.report(admin, new BillingReportService.ReportQuery(
				BillingReportService.Kind.PROFITABILITY, FROM, TO, null, BillingReportService.GroupBy.PROJECT), 0, 20);
		assertThat(profit.totals().costCents()).isEqualTo(12_000);
		assertThat(profit.totals().marginCents()).isEqualTo(6_000);
		assertThat(profit.groups().getContent()).singleElement()
				.satisfies(row -> assertThat(row.detail()).isEqualTo("HIN"));

		BillingReportService.Report utilization = reports.report(lead, new BillingReportService.ReportQuery(
				BillingReportService.Kind.UTILIZATION, FROM, TO, null, BillingReportService.GroupBy.PROJECT), 0, 20);
		assertThat(utilization.totals().billablePermille()).isEqualTo(500);

		assertThatThrownBy(() -> reports.report(lead, new BillingReportService.ReportQuery(
				BillingReportService.Kind.PROFITABILITY, FROM, TO, null, null), 0, 20))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> reports.report(member, new BillingReportService.ReportQuery(
				BillingReportService.Kind.BILLING, FROM, TO, null, null), 0, 20))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> reports.report(admin, new BillingReportService.ReportQuery(
				BillingReportService.Kind.PROFITABILITY, FROM, TO, null, BillingReportService.GroupBy.USER), 0, 20))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.BAD_REQUEST));
		// A lead sees nothing of a project they do not lead.
		assertThatThrownBy(() -> reports.report(lead, new BillingReportService.ReportQuery(
				BillingReportService.Kind.BILLING, FROM, TO, List.of(other.getId()), null), 0, 20))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
	}

	// --- invoices ---------------------------------------------------------------------------------

	private InvoiceService.Detail draft(User by, Project in) {
		return invoices.createDraft(by, new InvoiceService.DraftRequest(in.getId(), FROM, TO,
				Invoice.Grouping.ISSUE, new Invoice.Recipient("ACME GmbH", "Hauptstr. 1\n12345 Berlin", "DE123"),
				"Thanks", 1_900), Locale.GERMAN);
	}

	@Test
	void anInvoiceFromDraftToCreditNote() {
		rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT, project.getId(), null, 9_000, FROM);
		WorkItem billed = entry(member, project, LocalDate.of(2026, 9, 3), 90, true);
		entry(member, project, LocalDate.of(2026, 9, 4), 30, false);

		InvoiceService.Detail draft = draft(lead, project);
		assertThat(draft.summary().totals().netCents()).isEqualTo(13_500);
		assertThat(draft.summary().totals().taxCents()).isEqualTo(2_565);
		assertThat(draft.summary().number()).isNull();
		assertThat(invoices.lines(lead, draft.summary().id(), 0, 50).getContent()).singleElement()
				.satisfies(line -> {
					assertThat(line.entries()).isEqualTo(1);
					assertThat(line.description()).isEqualTo("Ohne Vorgang");
				});
		// A draft freezes nothing.
		assertThat(workItems.findById(billed.getId()).orElseThrow().getInvoiceId()).isNull();

		InvoiceService.Detail issued = invoices.issue(lead, draft.summary().id());
		assertThat(issued.summary().number()).isEqualTo("INV-2026-00001");
		assertThat(issued.summary().status()).isEqualTo(Invoice.Status.ISSUED);
		assertThat(workItems.findById(billed.getId()).orElseThrow().getInvoiceId()).isEqualTo(issued.summary().id());

		// Billed: no write path changes it, the owner and administrators included.
		TimeTrackingService.WorkItemPatch patch = new TimeTrackingService.WorkItemPatch(120, null, null, null,
				false, null, false, null, null, null);
		assertThatThrownBy(() -> timeTracking.update(billed.getId(), patch, member))
				.satisfies(thrown -> {
					assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(((ApiException) thrown).getDetails()).containsEntry("reason", "invoice")
							.containsEntry("remedy", "creditNote");
				});
		assertThatThrownBy(() -> timeTracking.delete(billed.getId(), admin))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> invoices.update(lead, draft.summary().id(),
				new InvoiceService.Change(null, "changed", null, null, null)))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));
		// Nothing left to bill in the period.
		assertThatThrownBy(() -> draft(lead, project))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.BAD_REQUEST));
		// The project keeps its booking records.
		assertThatThrownBy(() -> deletion.validateProjectDelete(project, admin, "delete", null))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));

		InvoiceService.Detail credit = invoices.creditNote(lead, draft.summary().id(), "Wrong hours");
		assertThat(credit.summary().kind()).isEqualTo(Invoice.Kind.CREDIT_NOTE);
		assertThat(credit.summary().number()).isEqualTo("INV-2026-00002");
		assertThat(credit.summary().totals().grossCents()).isEqualTo(-16_065);
		assertThat(credit.creditedInvoiceNumber()).isEqualTo("INV-2026-00001");
		assertThat(workItems.findById(billed.getId()).orElseThrow().getInvoiceId()).isNull();
		assertThatThrownBy(() -> invoices.creditNote(lead, draft.summary().id(), null))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));
		// Released for correction.
		assertThat(timeTracking.update(billed.getId(), patch, member).getDurationMinutes()).isEqualTo(120);
		assertThat(mongo.count(Query.query(Criteria.where("action").in(AuditAction.INVOICE_CREATED,
				AuditAction.INVOICE_ISSUED, AuditAction.INVOICE_CREDITED)), AuditLog.class)).isEqualTo(3);
	}

	@Test
	void aDraftWhoseEntriesChangedIsNotIssuedAndLeavesNothingBilled() {
		rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT, project.getId(), null, 9_000, FROM);
		WorkItem entry = entry(member, project, LocalDate.of(2026, 9, 3), 60, true);
		InvoiceService.Detail draft = draft(lead, project);
		timeTracking.update(entry.getId(), new TimeTrackingService.WorkItemPatch(75, null, null, null, false, null,
				false, null, null, null), member);

		assertThatThrownBy(() -> invoices.issue(lead, draft.summary().id()))
				.satisfies(thrown -> assertThat(((ApiException) thrown).getMessageKey())
						.isEqualTo("error.billing.invoice.stale"));
		assertThat(workItems.findById(entry.getId()).orElseThrow().getInvoiceId()).isNull();
		assertThat(invoices.get(lead, draft.summary().id()).summary().status()).isEqualTo(Invoice.Status.DRAFT);

		InvoiceService.Detail refreshed = invoices.refresh(lead, draft.summary().id());
		assertThat(refreshed.summary().totals().minutes()).isEqualTo(75);
		assertThat(invoices.issue(lead, draft.summary().id()).summary().number()).isEqualTo("INV-2026-00001");
	}

	@Test
	void unratedLinesAreNotIssued() {
		entry(member, project, LocalDate.of(2026, 9, 3), 60, true);
		InvoiceService.Detail draft = draft(lead, project);

		assertThat(draft.unratedLines()).isEqualTo(1);
		assertThatThrownBy(() -> invoices.issue(lead, draft.summary().id()))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void parallelIssuesGetGaplessNumbersAndOneDraftIsIssuedOnce() throws Exception {
		rate(admin, BillingRate.Kind.BILLABLE, BillingRate.Scope.DEFAULT, null, null, 9_000, FROM);
		int count = 8;
		List<String> drafts = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Project own = project("P" + i, lead);
			entry(member, own, LocalDate.of(2026, 9, 3), 60, true);
			drafts.add(draft(lead, own).summary().id());
		}
		ExecutorService pool = Executors.newFixedThreadPool(count);
		try {
			List<Callable<String>> calls = new ArrayList<>();
			for (String id : drafts) {
				calls.add(() -> invoices.issue(lead, id).summary().number());
			}
			// The same draft twice, at once: one number, one refusal.
			String twice = drafts.getFirst();
			List<Future<String>> results = pool.invokeAll(calls);
			Future<String> again = pool.submit(() -> invoices.issue(lead, twice).summary().number());
			List<String> numbers = new ArrayList<>();
			for (Future<String> result : results) {
				numbers.add(result.get());
			}
			assertThatThrownBy(again::get).hasCauseInstanceOf(ApiException.class);
			Collections.sort(numbers);
			List<String> expected = new ArrayList<>();
			for (int i = 1; i <= count; i++) {
				expected.add(String.format("INV-2026-%05d", i));
			}
			assertThat(numbers).isEqualTo(expected);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void anInvoiceExportsInItsOwnLanguage() {
		rate(lead, BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT, project.getId(), null, 9_000, FROM);
		entry(member, project, LocalDate.of(2026, 9, 3), 60, true);
		String id = invoices.issue(lead, draft(lead, project).summary().id()).summary().id();

		for (ExportFormat format : ExportFormat.values()) {
			Invoice invoice = exports.plan(lead, id);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			exports.write(lead, invoice, format, new ExportWords(messages, Locale.GERMAN, ZoneOffset.UTC), out);
			assertThat(out.size()).as(format.name()).isGreaterThan(500);
		}
		assertThat(InvoiceExport.money(-123_456, Invoice.builder().currency("EUR").build(),
				new ExportWords(messages, Locale.GERMAN, ZoneOffset.UTC), false)).isEqualTo("-1.234,56\u00a0€");
		assertThat(InvoiceExport.money(-123_456, Invoice.builder().currency("EUR").build(),
				new ExportWords(messages, Locale.GERMAN, ZoneOffset.UTC), true)).isEqualTo("-1234.56");
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.INVOICE_EXPORTED)), AuditLog.class))
				.isEqualTo(3);
		// A member who leads nothing does not learn the invoice exists.
		assertThatThrownBy(() -> exports.plan(member, id)).isInstanceOf(ApiException.class);
	}
}
