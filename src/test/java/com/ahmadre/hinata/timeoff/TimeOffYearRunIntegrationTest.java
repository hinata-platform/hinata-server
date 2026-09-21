package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.availability.HolidayController;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The turn of the leave year, the notices that let leave lapse, the proposals after a long illness,
 * the settlement of somebody leaving, the retention of sick details, and the report on absences and
 * balances (HIN-119) — against a real database, on a clock the test moves.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimeOffYearRunIntegrationTest.Clocks.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeOffYearRunIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final int DAY = TimeOffType.DAY;
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

	/** A clock the test sets to the day it needs: the turn of a year is a date, not a moment. */
	static final class MovableClock extends Clock {
		private volatile Instant now = Instant.parse("2026-06-15T09:00:00Z");

		void on(LocalDate day) {
			now = day.atTime(9, 0).toInstant(ZoneOffset.UTC);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	static final MovableClock CLOCK = new MovableClock();

	@TestConfiguration
	static class Clocks {
		@Bean
		@Primary
		Clock testClock() {
			return CLOCK;
		}
	}

	@MockitoBean
	private CurrentUser currentUser;

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private UserRepository users;
	@Autowired
	private TimeOffBalanceService balances;
	@Autowired
	private TimeOffTypeService types;
	@Autowired
	private TimeOffTypeRepository typeRepository;
	@Autowired
	private TimeOffRequestService requests;
	@Autowired
	private TimeOffYearRun yearRun;
	@Autowired
	private TimeOffNotices notices;
	@Autowired
	private TimeOffYearDesk desk;
	@Autowired
	private TimeOffRetention retention;
	@Autowired
	private TimeOffReportService reports;
	@Autowired
	private TimeOffReportExport exports;
	@Autowired
	private TimeOffLedgerRepository ledger;
	@Autowired
	private TimeOffYearRunRepository runs;
	@Autowired
	private HolidayController holidayApi;
	@Autowired
	private HinataProperties properties;
	@Autowired
	private MessageSource messages;

	private User admin;
	private User member;
	private User other;
	private TimeOffType vacation;
	private TimeOffType capped;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "time_off_types", "time_off_ledger", "time_off_entitlements",
				"time_off_employment", "time_off_requests", "time_off", "time_off_notices", "time_off_proposals",
				"time_off_year_runs", "time_off_retention_runs", "notifications", "audit_log", "server_settings",
				"holidays", "holiday_calendars", "working_schedules", "projects", "issues", "work_items")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		CLOCK.on(LocalDate.of(2025, 6, 15));
		admin = user("admin", Role.ADMIN);
		member = user("member", Role.MEMBER);
		other = user("other", Role.MEMBER);
		policy(block -> {
		});
		vacation = types.byKey(TimeOffType.SYSTEM_VACATION).orElseThrow();
		capped = typeRepository.save(TimeOffType.builder().key("capped").name("Capped leave")
				.kind(TimeOffType.Kind.VACATION).countsAgainstBalance(true).accrual(TimeOffType.Accrual.ANNUAL)
				.allowanceMilliDays(20 * DAY).carryover(TimeOffType.Carryover.CAPPED)
				.carryoverCapMilliDays(5 * DAY).active(true).build());
	}

	// --- the switches ------------------------------------------------------------------

	@Test
	@DisplayName("with the module off nothing runs, and with the report policy off the report is gone")
	void theSwitchesAreHonouredOutsideHttp() {
		policy(block -> block.setAbsenceManagementEnabled(false));
		assertThat(yearRun.run()).isEmpty();
		assertThat(notices.run()).isZero();
		assertThat(retention.run()).isEmpty();

		policy(block -> block.setAbsenceReportsEnabled(false));
		assertThatThrownBy(() -> reports.report(admin, query(null, TimeOffReportService.GroupBy.PERSON), 0, 10))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
	}

	// --- the turn of the year ------------------------------------------------------------

	@Test
	@DisplayName("the turn of the year carries once, and lets nothing lapse without a notice")
	void carryoverIsBookedOnceAndLapsesOnlyWithANotice() {
		balances.grant(admin, member.getId(), capped.getId(), 2025, null, null);
		balances.grant(admin, other.getId(), capped.getId(), 2025, null, null);
		CLOCK.on(LocalDate.of(2025, 10, 1));
		notices.sendNow(admin, member.getId(), capped.getId(), 2025);

		CLOCK.on(LocalDate.of(2026, 1, 2));
		assertThat(yearRun.run()).isPresent();
		// Told: five days carried, the rest lapsed at the end of the year.
		assertThat(sum(member, capped, 2025)).isZero();
		assertThat(kind(member, capped, 2025, TimeOffLedgerEntry.Kind.EXPIRED)).isEqualTo(-15 * DAY);
		assertThat(sum(member, capped, 2026)).isEqualTo(5 * DAY);
		// Never told: everything carried, nothing lapsed.
		assertThat(kind(other, capped, 2025, TimeOffLedgerEntry.Kind.EXPIRED)).isZero();
		assertThat(sum(other, capped, 2026)).isEqualTo(20 * DAY);

		long rows = ledger.count();
		// The same night again is claimed; a second instance that ignored the claim collides on the keys.
		assertThat(yearRun.run()).isEmpty();
		runs.deleteAll();
		assertThat(yearRun.run()).isPresent();
		assertThat(ledger.count()).isEqualTo(rows);

		// After the carryover deadline the carried days lapse for the one who was told, and stay for the other.
		CLOCK.on(LocalDate.of(2026, 4, 1));
		yearRun.run();
		assertThat(kind(member, capped, 2026, TimeOffLedgerEntry.Kind.EXPIRED)).isEqualTo(-5 * DAY);
		assertThat(sum(member, capped, 2026)).isZero();
		assertThat(kind(other, capped, 2026, TimeOffLedgerEntry.Kind.EXPIRED)).isZero();
		assertThat(sum(other, capped, 2026)).isEqualTo(20 * DAY);
		assertThat(runs.findFirstByOrderByIdDesc().orElseThrow().getHeld()).isEqualTo(1);
	}

	@Test
	@DisplayName("a notice is audited, delivered, and the person reads the same one")
	void aNoticeIsAuditedAndThePersonSeesTheSameOne() {
		CLOCK.on(LocalDate.of(2026, 3, 1));
		balances.grant(admin, member.getId(), capped.getId(), 2026, null, null);

		CLOCK.on(LocalDate.of(2026, 10, 1));
		assertThat(notices.run()).isEqualTo(1);
		assertThat(notices.run()).isZero();

		TimeOffNotice sent = notices.of(member, null, 0, 10).getContent().getFirst();
		assertThat(sent.getKind()).isEqualTo(TimeOffNotice.Kind.ANNUAL);
		assertThat(sent.remainingMilliDays()).isEqualTo(20 * DAY);
		assertThat(sent.getExpiresOn()).isEqualTo(LocalDate.of(2026, 12, 31));
		AuditLog logged = mongo.findOne(Query.query(Criteria.where("action")
				.is(AuditAction.TIME_OFF_EXPIRY_NOTICE_SENT)), AuditLog.class);
		assertThat(logged).isNotNull();
		assertThat(logged.getTargetId()).isEqualTo(member.getId());
		assertThat(logged.getMetadata()).containsEntry("notice", sent.getId())
				.containsEntry("milliDays", String.valueOf(20 * DAY)).containsEntry("expiresOn", "2026-12-31");
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(member.getId())
				.and("type").is(Notification.Type.TIME_OFF_EXPIRY_NOTICE)), Notification.class)).isEqualTo(1);
		// Somebody else's notices are a keeper's to read.
		assertThatThrownBy(() -> notices.of(other, member.getId(), 0, 10)).isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("a long illness opens a proposal, and only a keeper with a reason lets the days lapse")
	void longIllnessProposesAndAKeeperDecides() {
		CLOCK.on(LocalDate.of(2024, 6, 15));
		balances.grant(admin, member.getId(), vacation.getId(), 2024, null, null);
		sick(member, LocalDate.of(2024, 2, 1), LocalDate.of(2026, 4, 30));
		for (LocalDate day : List.of(LocalDate.of(2025, 1, 2), LocalDate.of(2025, 4, 1),
				LocalDate.of(2026, 1, 2), LocalDate.of(2026, 4, 1))) {
			CLOCK.on(day);
			yearRun.run();
		}
		assertThat(kind(member, vacation, 2026, TimeOffLedgerEntry.Kind.EXPIRED)).isZero();
		TimeOffProposal proposal = desk.openProposals(admin, 0, 10).getContent().getFirst();
		assertThat(proposal.getYear()).isEqualTo(2024);
		assertThat(proposal.milliDays()).isEqualTo(20 * DAY);

		assertThatThrownBy(() -> desk.confirm(admin, proposal.getId(), "  ")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> desk.confirm(member, proposal.getId(), "ill throughout"))
				.isInstanceOf(ApiException.class);
		desk.confirm(admin, proposal.getId(), "Continuously unable to work since February 2024");
		assertThat(kind(member, vacation, 2026, TimeOffLedgerEntry.Kind.EXPIRED)).isEqualTo(-20 * DAY);
		assertThatThrownBy(() -> desk.confirm(admin, proposal.getId(), "again"))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
	}

	@Test
	@DisplayName("a monthly type earns a twelfth each month, once")
	void monthlyAccrualTopsUpOncePerMonth() {
		TimeOffType monthly = typeRepository.save(TimeOffType.builder().key("monthly").name("Monthly")
				.kind(TimeOffType.Kind.VACATION).countsAgainstBalance(true).accrual(TimeOffType.Accrual.MONTHLY)
				.allowanceMilliDays(12 * DAY).carryover(TimeOffType.Carryover.NONE).active(true).build());
		CLOCK.on(LocalDate.of(2026, 3, 15));
		balances.grant(admin, member.getId(), monthly.getId(), 2026, null, null);
		assertThat(sum(member, monthly, 2026)).isEqualTo(2 * DAY);

		CLOCK.on(LocalDate.of(2026, 5, 2));
		yearRun.run();
		runs.deleteAll();
		yearRun.run();
		assertThat(sum(member, monthly, 2026)).isEqualTo(4 * DAY);
	}

	// --- retention ----------------------------------------------------------------------

	@Test
	@DisplayName("after a year a sick day reads as away, and the balance does not move")
	void retentionCoarsensSickDetailsAndKeepsTheBalance() {
		CLOCK.on(LocalDate.of(2026, 6, 15));
		balances.grant(admin, member.getId(), vacation.getId(), 2026, null, null);
		TimeOff old = sick(member, LocalDate.of(2025, 1, 6), LocalDate.of(2025, 1, 10));
		TimeOff recent = sick(member, LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 5));
		mongo.insert(TimeOffRequest.builder().userId(member.getId()).typeId(vacation.getId())
				.from(LocalDate.of(2022, 3, 1)).to(LocalDate.of(2022, 3, 2)).status(TimeOffRequest.Status.REJECTED)
				.build());
		int before = sum(member, vacation, 2026);

		assertThat(retention.run()).isPresent();
		TimeOff coarsened = mongo.findById(old.getId(), TimeOff.class);
		assertThat(coarsened.getType()).isEqualTo(TimeOff.Type.OTHER);
		assertThat(coarsened.getNote()).isNull();
		assertThat(mongo.findById(recent.getId(), TimeOff.class).getType()).isEqualTo(TimeOff.Type.SICK);
		assertThat(mongo.count(new Query(), TimeOffRequest.class)).isZero();
		assertThat(sum(member, vacation, 2026)).isEqualTo(before);
	}

	// --- leaving -----------------------------------------------------------------------

	@Test
	@DisplayName("leaving in the first half keeps twelfths, and a person books the payout")
	void settlementComputesTwelfthsAndThePayoutNeedsAPerson() {
		CLOCK.on(LocalDate.of(2026, 3, 15));
		balances.grant(admin, member.getId(), vacation.getId(), 2026, null, null);
		balances.saveEmployment(admin, member.getId(), LocalDate.of(2020, 1, 1), LocalDate.of(2026, 4, 30), null);

		TimeOffYearDesk.Settlement row = desk.settlement(admin, member.getId()).stream()
				.filter(candidate -> candidate.typeId().equals(vacation.getId())).findFirst().orElseThrow();
		// Four full months of twenty days is 6.67, which § 5 Abs. 2 BUrlG rounds up to seven.
		assertThat(row.accruedMilliDays()).isEqualTo(7 * DAY);
		assertThat(row.correctionMilliDays()).isEqualTo(-13 * DAY);
		assertThat(row.payoutMilliDays()).isEqualTo(7 * DAY);

		assertThatThrownBy(() -> desk.payout(admin, member.getId(), vacation.getId(), 2026, 7 * DAY, " "))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> desk.payout(admin, member.getId(), vacation.getId(), 2026, 21 * DAY, "too much"))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> desk.payout(member, member.getId(), vacation.getId(), 2026, DAY, "myself"))
				.isInstanceOf(ApiException.class);
		balances.adjust(admin, member.getId(), vacation.getId(), 2026, -13 * DAY, null, "Twelfths, § 5 BUrlG");
		desk.payout(admin, member.getId(), vacation.getId(), 2026, 7 * DAY, "Settled with the final pay");
		assertThat(sum(member, vacation, 2026)).isZero();
		assertThat(kind(member, vacation, 2026, TimeOffLedgerEntry.Kind.PAYOUT)).isEqualTo(-7 * DAY);
	}

	// --- the report ----------------------------------------------------------------------

	@Test
	@DisplayName("the report adds up a carryover, a half day, a holiday and a cancellation")
	void theReportAddsUpTheReferenceCase() throws Exception {
		when(currentUser.require()).thenReturn(admin);
		HolidayController.CalendarResponse calendar = holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Deutschland", "Bundesweit", null, true));
		holidayApi.addHoliday(new HolidayController.HolidayRequest(calendar.id(), LocalDate.of(2026, 5, 14),
				"Christi Himmelfahrt", null));
		balances.grant(admin, member.getId(), vacation.getId(), 2025, null, null);
		CLOCK.on(LocalDate.of(2026, 1, 2));
		balances.grant(admin, member.getId(), vacation.getId(), 2026, null, null);
		yearRun.run();

		CLOCK.on(LocalDate.of(2026, 3, 10));
		approve(new TimeOffRequestService.Draft(vacation.getId(), LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 20),
				DAY / 2, null, null, null));
		approve(new TimeOffRequestService.Draft(vacation.getId(), LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 15),
				null, null, null, null));
		TimeOffRequest cancelled = approve(new TimeOffRequestService.Draft(vacation.getId(), LocalDate.of(2026, 4, 6),
				LocalDate.of(2026, 4, 7), null, null, null, null));
		requests.cancel(cancelled.getId(), null, admin);

		CLOCK.on(LocalDate.of(2026, 3, 25));
		TimeOffReportService.Report report = reports.report(admin, query(vacation.getId(),
				TimeOffReportService.GroupBy.PERSON), 0, 10);
		TimeOffReportService.Figures figures = report.rows().getContent().stream()
				.filter(row -> member.getId().equals(row.userId())).findFirst().orElseThrow().figures();
		assertThat(figures.entitledMilliDays()).isEqualTo(20 * DAY);
		assertThat(figures.carriedInMilliDays()).isEqualTo(20 * DAY);
		assertThat(figures.takenMilliDays()).isEqualTo(DAY / 2);
		// Monday to Friday with the holiday on Thursday: four days, still ahead.
		assertThat(figures.plannedMilliDays()).isEqualTo(4 * DAY);
		assertThat(figures.remainingMilliDays()).isEqualTo(35 * DAY + DAY / 2);
		assertThat(figures.expiringMilliDays()).isEqualTo(20 * DAY);
		assertThat(figures.expiringOn()).isEqualTo(LocalDate.of(2026, 3, 31));
		// The person's own card names the same deadline for what came in, not next year's.
		TimeOffBalanceService.Balance own2026 = balances.balances(admin, member.getId(), 2026).stream()
				.filter(balance -> vacation.getId().equals(balance.typeId())).findFirst().orElseThrow();
		assertThat(own2026.expiresOn()).isEqualTo(LocalDate.of(2026, 3, 31));
		// Without its own switch the rate is not blank but absent.
		assertThat(report.rateVisible()).isFalse();
		JsonNode json = JSON.valueToTree(figures);
		assertThat(json.has("ratePermille")).isFalse();

		policy(block -> block.setAbsenceRateEnabled(true));
		TimeOffReportService.Figures withRate = reports.report(admin, query(vacation.getId(),
						TimeOffReportService.GroupBy.PERSON), 0, 10).rows().getContent().stream()
				.filter(row -> member.getId().equals(row.userId())).findFirst().orElseThrow().figures();
		assertThat(JSON.valueToTree(withRate).has("ratePermille")).isTrue();
		// A member reads themselves only, and never the rate.
		TimeOffReportService.Report own = reports.report(member, query(vacation.getId(),
				TimeOffReportService.GroupBy.PERSON), 0, 10);
		assertThat(own.rateVisible()).isFalse();
		assertThat(own.rows().getContent()).extracting(TimeOffReportService.Row::userId)
				.containsExactly(member.getId());
	}

	@Test
	@DisplayName("a lead reads sums per type over three or more, never people and never sickness")
	void aLeadSeesSumsWithoutSickness() throws Exception {
		User lead = user("lead", Role.MEMBER);
		Project project = mongo.insert(Project.builder().key("HIN").name("Hinata")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId(), other.getId()))).build());
		Issue issue = mongo.insert(Issue.builder().projectId(project.getId()).numberInProject(1).readableId("HIN-1")
				.title("HIN-1").formerReadableIds(new ArrayList<>()).build());
		for (User worker : List.of(lead, member, other)) {
			mongo.insert(WorkItem.builder().userId(worker.getId()).projectId(project.getId()).issueId(issue.getId())
					.date(LocalDate.of(2025, 6, 1)).durationMinutes(60).activityType("Development")
					.source(WorkItem.Source.APP).build());
		}
		TimeOffType sickQuota = typeRepository.save(TimeOffType.builder().key("sickquota").name("Sick quota")
				.kind(TimeOffType.Kind.SICK).countsAgainstBalance(true).allowanceMilliDays(5 * DAY).active(true)
				.build());
		balances.grant(admin, member.getId(), vacation.getId(), 2025, null, null);
		balances.grant(admin, member.getId(), sickQuota.getId(), 2025, null, null);

		assertThatThrownBy(() -> reports.report(lead, new TimeOffReportService.ReportQuery(2025, null, null,
				project.getId(), null, TimeOffReportService.GroupBy.TYPE), 0, 10))
				.isInstanceOf(ApiException.class);
		policy(block -> block.setLeadsSeeMemberEntries(true));
		assertThatThrownBy(() -> reports.report(lead, new TimeOffReportService.ReportQuery(2025, null, null,
				project.getId(), null, TimeOffReportService.GroupBy.PERSON), 0, 10))
				.isInstanceOf(ApiException.class);
		TimeOffReportService.Report sums = reports.report(lead, new TimeOffReportService.ReportQuery(2025, null,
				null, project.getId(), null, TimeOffReportService.GroupBy.TYPE), 0, 10);
		assertThat(sums.rows().getContent()).extracting(TimeOffReportService.Row::typeId)
				.contains(vacation.getId()).doesNotContain(sickQuota.getId());
		assertThat(sums.rows().getContent()).allSatisfy(row -> assertThat(row.userId()).isNull());
		assertThat(JSON.writeValueAsString(sums.totals())).doesNotContain("ratePermille");
	}

	@Test
	@DisplayName("the report leaves in four formats, defused, within the caller's budget")
	void theReportExportsInFourFormats() throws Exception {
		User odd = users.save(User.builder().email("odd@example.org").username("odd")
				.displayName("=HYPERLINK(\"x\")").roles(Set.of(Role.MEMBER)).active(true).timezone("UTC")
				.locale("en").build());
		balances.grant(admin, odd.getId(), vacation.getId(), 2025, null, null);
		TimeOffReportService.ReportQuery query = query(vacation.getId(), TimeOffReportService.GroupBy.PERSON);
		ExportWords words = new ExportWords(messages, Locale.ENGLISH, ZoneOffset.UTC);

		ByteArrayOutputStream csv = new ByteArrayOutputStream();
		try (TimeOffReportExport.Plan plan = exports.plan(admin, query, null)) {
			exports.writeCsv(plan, words, csv);
		}
		String text = csv.toString(StandardCharsets.UTF_8);
		assertThat(text).contains("Remaining").contains("HYPERLINK")
				.doesNotContain("\n=HYPERLINK").doesNotContain("\n\"=HYPERLINK");
		for (ExportFormat format : ExportFormat.values()) {
			ByteArrayOutputStream file = new ByteArrayOutputStream();
			try (TimeOffReportExport.Plan plan = exports.plan(admin, query, format)) {
				exports.writeDocument(plan, format, words, file);
			}
			assertThat(file.size()).as(format.name()).isPositive();
		}
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.TIME_OFF_REPORT_EXPORTED)),
				AuditLog.class)).isEqualTo(1 + ExportFormat.values().length);

		int before = properties.getRateLimit().getExportsPerMinute();
		properties.getRateLimit().setExportsPerMinute(1);
		User exporter = user("budget-" + System.nanoTime(), Role.ADMIN);
		try {
			exports.plan(exporter, query, null).close();
			assertThatThrownBy(() -> exports.plan(exporter, query, null))
					.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(429));
		}
		finally {
			properties.getRateLimit().setExportsPerMinute(before);
		}
	}

	// --- fixtures ---------------------------------------------------------------------

	private TimeOffReportService.ReportQuery query(String typeId, TimeOffReportService.GroupBy groupBy) {
		return new TimeOffReportService.ReportQuery(null, typeId, null, null, null, groupBy);
	}

	private TimeOffRequest approve(TimeOffRequestService.Draft draft) {
		TimeOffRequest filed = requests.submit(member, draft);
		return requests.approve(filed.getId(), null, admin);
	}

	private TimeOff sick(User person, LocalDate from, LocalDate to) {
		return mongo.insert(TimeOff.builder().userId(person.getId()).type(TimeOff.Type.SICK)
				.typeId(types.byKey(TimeOffType.SYSTEM_SICK).map(TimeOffType::getId).orElse(null))
				.from(from).to(to).note("flu").createdBy(person.getId()).build());
	}

	private int sum(User person, TimeOffType type, int year) {
		return ledger.findAll().stream()
				.filter(row -> row.getUserId().equals(person.getId()) && row.getTypeId().equals(type.getId())
						&& row.getYear() == year)
				.mapToInt(TimeOffLedgerEntry::milliDays).sum();
	}

	private int kind(User person, TimeOffType type, int year, TimeOffLedgerEntry.Kind kind) {
		return ledger.findAll().stream()
				.filter(row -> row.getUserId().equals(person.getId()) && row.getTypeId().equals(type.getId())
						&& row.getYear() == year && row.getKind() == kind)
				.mapToInt(TimeOffLedgerEntry::milliDays).sum();
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").locale("en")
				.build());
	}

	/** The module on, the report on, and whatever else [change] sets. */
	private void policy(Consumer<ServerSettings.TimeTracking> change) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setAbsenceManagementEnabled(true);
		block.setAbsenceReportsEnabled(true);
		change.accept(block);
		current.setTimeTracking(block);
		settings.save(current);
	}
}
