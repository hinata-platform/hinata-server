package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.me.DataExportPdfService;
import com.ahmadre.hinata.me.MeService;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Stage 8 (HIN-89): the rights of the person whose working time this is, as
 * functions — being told, getting a copy, having it corrected, having it erased —
 * and the self-hints that are shown to that person and to nobody else.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimePrivacyIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimePrivacyIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);

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
	private HinataProperties properties;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private UserService userService;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private TimesheetApprovalRepository approvals;
	@Autowired
	private RunningTimerRepository timers;
	@Autowired
	private DepartedTimeUserRepository departed;
	@Autowired
	private TimeBackfillGrantRepository grants;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private TimeTrackingController workItemsApi;
	@Autowired
	private IssueRepository issues;
	@Autowired
	private TimePrivacyController privacy;
	@Autowired
	private TimeHintsController hints;
	@Autowired
	private TimeExportController export;
	@Autowired
	private TimeEntryCsvExport csv;
	@Autowired
	private TimeCorrectionService corrections;
	@Autowired
	private TimeCorrectionRequestBackfill backfill;
	@Autowired
	private TimeLockExceptionController lockExceptions;
	@Autowired
	private MeService me;
	@Autowired
	private DataExportPdfService pdf;
	@MockitoBean
	private CurrentUser currentUser;

	private User member;
	private User lead;
	private User admin;
	private Project project;

	@BeforeEach
	void seed() {
		for (String collection : List.of("projects", "issues", "users", "work_items", "running_timers",
				"timesheet_approvals", "audit_log", "notifications", "server_settings",
				"time_departed_users", "time_retention_runs", "time_correction_requests",
				"time_backfill_grants")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		properties.getTimeTracking().setMaxDaysBack(TimePolicy.MAX_DAYS_BACK_DEFAULT);
		properties.getTimeTracking().setLateEntryHintDays(null);
		properties.getTimeTracking().setArbzgHintsEnabled(false);
		properties.getRateLimit().setExportsPerMinute(12);
		policy(block -> {
		});
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		member = user("member", Role.MEMBER, "de");
		lead = user("lead", Role.MEMBER, "en");
		admin = user("admin", Role.ADMIN, "en");
		project.getLeadIds().add(lead.getId());
		project = projects.save(project);
	}

	private User user(String name, Role role, String locale) {
		User saved = users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").locale(locale)
				.build());
		project.getMemberIds().add(saved.getId());
		project = projects.save(project);
		return saved;
	}

	/** Replaces the stored block with a fresh one the module is on in, then edits it. */
	private void policy(Consumer<ServerSettings.TimeTracking> edit) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = current.getTimeTracking() != null
				? current.getTimeTracking() : new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		edit.accept(block);
		current.setTimeTracking(block);
		settings.save(current);
	}

	private void as(User user) {
		when(currentUser.require()).thenReturn(users.findById(user.getId()).orElseThrow());
	}

	private WorkItem entry(User who, LocalDate day, int minutes, String description) {
		return workItems.save(WorkItem.builder().userId(who.getId()).projectId(project.getId())
				.date(day).durationMinutes(minutes).description(description).tags(List.of())
				.source(WorkItem.Source.APP).build());
	}

	private TimeTrackingService.NewEntry typed(LocalDate day) {
		return new TimeTrackingService.NewEntry(project.getId(), null, 60, day, null, "worked",
				null, null, List.of(), null);
	}

	private static TimeTrackingService.WorkItemPatch minutes(int minutes) {
		return new TimeTrackingService.WorkItemPatch(minutes, null, null, null, false, null, false,
				null, null, null);
	}

	private List<Notification> notificationsOf(Notification.Type type) {
		return notifications.findAll().stream().filter(n -> n.getType() == type).toList();
	}

	private long recordsOf(AuditAction action) {
		return mongo.count(new Query(Criteria.where("action").is(action)), AuditLog.class);
	}

	private static HttpStatus statusOf(Throwable thrown) {
		return ((ApiException) thrown).getStatus();
	}

	// --- transparency ------------------------------------------------------------

	@Test
	void theVisibilityFollowsThePoliciesAsTheyStandWithoutARestart() {
		record Combination(boolean leads, boolean approvals, boolean arbzg, Integer lateDays) {
		}
		for (Combination combination : List.of(
				new Combination(false, false, false, null),
				new Combination(true, false, true, null),
				new Combination(false, true, false, 7),
				new Combination(true, true, true, 14))) {
			policy(block -> {
				block.setLeadsSeeMemberEntries(combination.leads());
				block.setApprovalsEnabled(combination.approvals());
				block.setArbzgHintsEnabled(combination.arbzg());
				block.setLateEntryHintDays(combination.lateDays() == null ? 0 : combination.lateDays());
			});
			as(member);

			TimePrivacyService.Visibility visibility = privacy.privacy().visibility();

			assertThat(visibility.leadsSeeEntries()).as("%s", combination).isEqualTo(combination.leads());
			assertThat(visibility.approvalsEnabled()).as("%s", combination).isEqualTo(combination.approvals());
			assertThat(visibility.arbzgHints()).as("%s", combination).isEqualTo(combination.arbzg());
			assertThat(visibility.lateEntryHintDays()).as("%s", combination).isEqualTo(combination.lateDays());
		}
	}

	@Test
	void theNoticeIsTheTemplateInTheReadersLanguageUntilTheOperatorWritesTheirOwn() {
		as(member);
		TimePrivacyService.Privacy builtIn = privacy.privacy();
		assertThat(builtIn.customNotice()).isFalse();
		assertThat(builtIn.notice()).contains("Deine Arbeitszeit in Hinata");

		policy(block -> block.setPrivacyNotice("Unsere Betriebsvereinbarung regelt das."));

		TimePrivacyService.Privacy custom = privacy.privacy();
		assertThat(custom.customNotice()).isTrue();
		assertThat(custom.notice()).isEqualTo("Unsere Betriebsvereinbarung regelt das.");
	}

	@Test
	void confirmingTheNoticeKeepsTheFirstMoment() {
		as(member);
		assertThat(privacy.privacy().acknowledgedAt()).isNull();

		assertThat(privacy.acknowledge().acknowledgedAt()).isEqualTo(NOW);

		Instant earlier = NOW.minusSeconds(86_400);
		User stored = users.findById(member.getId()).orElseThrow();
		stored.setTimePrivacyAcknowledgedAt(earlier);
		users.save(stored);
		as(member);
		assertThat(privacy.acknowledge().acknowledgedAt()).isEqualTo(earlier);
	}

	// --- self-hints and R9 --------------------------------------------------------

	@Test
	void theHintsDoNotExistWhileBothPoliciesAreOff() {
		as(member);

		assertThatThrownBy(() -> hints.hints(TODAY.minusDays(7), TODAY))
				.isInstanceOf(ApiException.class)
				.extracting(TimePrivacyIntegrationTest::statusOf)
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void theHintsAnswerForOneMonthAtATime() {
		policy(block -> block.setArbzgHintsEnabled(true));
		as(member);

		assertThat(hints.hints(TODAY.minusDays(30), TODAY).hints()).isEmpty();
		assertThatThrownBy(() -> hints.hints(TODAY.minusDays(31), TODAY))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.rangeTooLong");
	}

	@Test
	void aLateEntryIsSavedAndHintedToItsOwnerAndToNobodyElse() {
		policy(block -> block.setLateEntryHintDays(7));
		LocalDate ninetyDaysAgo = TODAY.minusDays(90);

		// Recorded, not refused: § 16 Abs. 2 ArbZG knows no deadline.
		WorkItem late = timeTracking.create(typed(ninetyDaysAgo), WorkItem.Source.APP, member);
		assertThat(workItems.findById(late.getId())).isPresent();

		as(member);
		assertThat(hints.hints(ninetyDaysAgo, ninetyDaysAgo.plusDays(30)).hints())
				.singleElement()
				.satisfies(hint -> {
					assertThat(hint.kind()).isEqualTo(WorkingTimeHints.Kind.LATE_ENTRY);
					assertThat(hint.entryId()).isEqualTo(late.getId());
				});
		// Nobody else learns it: the route has no person to ask about, a lead asking
		// sees only their own (none), and nothing was sent or recorded anywhere.
		as(lead);
		assertThat(hints.hints(ninetyDaysAgo, ninetyDaysAgo.plusDays(30)).hints()).isEmpty();
		assertThat(notifications.findAll()).isEmpty();
		assertThat(mongo.findAll(AuditLog.class)).noneMatch(log -> late.getId().equals(
				log.getMetadata() == null ? null : log.getMetadata().get("workItem")));

		policy(block -> {
			block.setLateEntryHintDays(0);
			block.setArbzgHintsEnabled(true);
		});
		as(member);
		assertThat(hints.hints(ninetyDaysAgo, ninetyDaysAgo.plusDays(30)).hints()).isEmpty();
	}

	@Test
	void theRecordingLimitIsAPolicyAndItsRefusalNamesTheWayOut() {
		assertThat(timeTracking.create(typed(TODAY.minusDays(300)), WorkItem.Source.APP, member))
				.isNotNull();

		policy(block -> block.setMaxDaysBack(30));
		LocalDate tooOld = TODAY.minusDays(45);

		assertThatThrownBy(() -> timeTracking.create(typed(tooOld), WorkItem.Source.APP, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.dateBeyondLimit")
				.satisfies(thrown -> {
					ApiException refusal = (ApiException) thrown;
					assertThat(refusal.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(refusal.getDetails()).containsEntry("reason", "maxDaysBack")
							.containsEntry("holder", "admin")
							.containsEntry("remedy", "backfillGrant")
							.containsEntry("lockDate", TODAY.minusDays(30).toString());
				});

		// A lock exception still opens the days too, for everyone, as it always did.
		TimeLockExceptionController.LockExceptionRequest open =
				new TimeLockExceptionController.LockExceptionRequest();
		open.setFrom(tooOld);
		open.setTo(tooOld);
		open.setNote("Umstellung der Buchhaltung");
		as(admin);
		lockExceptions.add(open);

		assertThat(timeTracking.create(typed(tooOld), WorkItem.Source.APP, member).getDate())
				.isEqualTo(tooOld);
	}

	@Test
	void withTheModuleOffTheOldYearLimitStillAnswersInItsOldWords() {
		policy(block -> {
			block.setAdvancedEnabled(false);
			block.setMaxDaysBack(3000);
		});

		assertThatThrownBy(() -> timeTracking.create(typed(TODAY.minusDays(400)),
				WorkItem.Source.APP, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.dateTooOld");
	}

	// --- rectification -------------------------------------------------------------

	@Test
	void aCorrectionOfALockedDayWorksWithoutApprovalsAndItsAnswerReachesTheOwner() {
		policy(block -> block.setLockBefore(TODAY));
		WorkItem frozen = entry(member, TODAY.minusDays(5), 60, "Workshop");

		corrections.request(frozen.getId(), "Es waren 90 Minuten", member);

		assertThat(notificationsOf(Notification.Type.TIME_CORRECTION_REQUESTED))
				.extracting(Notification::getUserId).containsExactly(admin.getId());
		assertThatThrownBy(() -> corrections.request(frozen.getId(), "noch einmal", member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.correctionAlreadyRequested");
		assertThat(corrections.inbox(0, 20, admin).getContent()).singleElement()
				.satisfies(request -> {
					assertThat(request.kind()).isEqualTo(TimeCorrectionRequest.Kind.ENTRY);
					assertThat(request.entryId()).isEqualTo(frozen.getId());
					assertThat(request.note()).isEqualTo("Es waren 90 Minuten");
					assertThat(request.requesterLabel()).isEqualTo("member");
					assertThat(request.answer()).isNull();
				});
		// The lock date is not a lead's to open, so the request is not theirs to answer.
		assertThat(corrections.inbox(0, 20, lead).getContent()).isEmpty();
		String requestId = corrections.inbox(0, 20, admin).getContent().getFirst().id();
		assertThatThrownBy(() -> corrections.answer(requestId, "ok", lead))
				.isInstanceOf(ApiException.class)
				.extracting(TimePrivacyIntegrationTest::statusOf)
				.isEqualTo(HttpStatus.NOT_FOUND);

		TimeCorrectionService.CorrectionRequest answered =
				corrections.answer(requestId, "Ich öffne den Tag heute noch.", admin);

		assertThat(answered.answer().note()).isEqualTo("Ich öffne den Tag heute noch.");
		assertThat(answered.answer().granted()).isFalse();
		assertThat(notificationsOf(Notification.Type.TIME_CORRECTION_ANSWERED))
				.extracting(Notification::getUserId).containsExactly(member.getId());
		assertThat(corrections.inbox(0, 20, admin).getContent().getFirst().answer()).isNotNull();
		assertThatThrownBy(() -> corrections.answer(requestId, "again", admin))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.correctionAlreadyAnswered");
		// The owner reads the answer on the entry itself, whatever the audit log records,
		// and in the entry's history, sentence included.
		assertThat(corrections.forEntry(frozen.getId(), member)).singleElement()
				.satisfies(request -> assertThat(request.answer().note())
						.isEqualTo("Ich öffne den Tag heute noch."));
		TimeTrackingService.EntryHistory history = timeTracking.history(frozen.getId(), 0, 20, member);
		assertThat(history.rows().getContent().stream()
				.map(log -> TimeEntryController.HistoryEntryResponse.from(log, history.readsConversation(log))))
				.anySatisfy(row -> {
					assertThat(row.action()).isEqualTo(AuditAction.TIME_CORRECTION_ANSWERED.name());
					assertThat(row.metadata()).containsEntry("note", "Ich öffne den Tag heute noch.");
				});
	}

	@Test
	void aLeadWhoMaySeeTheEntryDoesNotReadTheConversationAboutTheLockDate() {
		policy(block -> {
			block.setLockBefore(TODAY);
			block.setLeadsSeeMemberEntries(true);
		});
		WorkItem frozen = entry(member, TODAY.minusDays(5), 60, "Workshop");
		corrections.request(frozen.getId(), "Es waren 90 Minuten", member);
		String requestId = corrections.inbox(0, 20, admin).getContent().getFirst().id();
		corrections.answer(requestId, "Ich öffne den Tag heute noch.", admin);

		TimeTrackingService.EntryHistory seenByLead = timeTracking.history(frozen.getId(), 0, 20, lead);

		assertThat(seenByLead.rows().getContent()).hasSize(2);
		assertThat(seenByLead.rows().getContent().stream()
				.map(log -> TimeEntryController.HistoryEntryResponse.from(log, seenByLead.readsConversation(log))))
				.allSatisfy(row -> assertThat(row.metadata()).doesNotContainKeys("note", "reason"));
	}

	@Test
	void nobodyAnswersTheirOwnRequest() {
		policy(block -> block.setLockBefore(TODAY));
		WorkItem ownFrozen = entry(admin, TODAY.minusDays(5), 60, "Admin work");
		corrections.request(ownFrozen.getId(), "Bitte öffnen", admin);
		String requestId = mongo.findAll(TimeCorrectionRequest.class).getFirst().getId();

		assertThat(corrections.inbox(0, 20, admin).getContent()).isEmpty();
		assertThatThrownBy(() -> corrections.answer(requestId, "ok", admin))
				.isInstanceOf(ApiException.class)
				.extracting(TimePrivacyIntegrationTest::statusOf)
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aGrantOpensTheLockedDayForThePersonWhoAskedAndForNobodyElse() {
		policy(block -> block.setLockBefore(TODAY));
		WorkItem frozen = entry(member, TODAY.minusDays(5), 60, "Workshop");
		WorkItem neighbour = entry(lead, TODAY.minusDays(5), 30, "Review");
		corrections.request(frozen.getId(), "Es waren 90 Minuten", member);
		TimeCorrectionService.CorrectionRequest asked =
				corrections.inbox(0, 20, admin).getContent().getFirst();
		assertThat(asked.grantable()).isTrue();

		TimeCorrectionService.CorrectionRequest granted =
				corrections.grant(asked.id(), "Bitte selbst korrigieren.", admin);

		assertThat(granted.answer().granted()).isTrue();
		assertThat(timeTracking.update(frozen.getId(), minutes(90), member).getDurationMinutes())
				.isEqualTo(90);
		assertThatThrownBy(() -> timeTracking.update(neighbour.getId(), minutes(45), lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.locked");
		assertThat(notificationsOf(Notification.Type.TIME_CORRECTION_ANSWERED))
				.extracting(Notification::getUserId).containsExactly(member.getId());
		assertThat(recordsOf(AuditAction.TIME_BACKFILL_GRANTED)).isEqualTo(1);
	}

	@Test
	void openingTheDaysNeedsNoSentenceWhileAnAnswerIsNothingWithoutOne() {
		policy(block -> block.setLockBefore(TODAY));
		WorkItem frozen = entry(member, TODAY.minusDays(5), 60, "Workshop");
		WorkItem other = entry(member, TODAY.minusDays(6), 30, "Review");
		corrections.request(frozen.getId(), "Es waren 90 Minuten", member);
		corrections.request(other.getId(), "Falsches Projekt", member);
		List<TimeCorrectionService.CorrectionRequest> asked = corrections.inbox(0, 20, admin).getContent();

		TimeCorrectionService.CorrectionRequest granted = corrections.grant(asked.get(0).id(), "  ", admin);

		assertThat(granted.answer().granted()).isTrue();
		assertThat(granted.answer().note()).isNull();
		assertThat(recordsOf(AuditAction.TIME_BACKFILL_GRANTED)).isEqualTo(1);
		assertThatThrownBy(() -> corrections.answer(asked.get(1).id(), " ", admin))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalNoteRequired");
	}

	@Test
	void daysBeyondTheLimitAreOpenedForThePersonWhoAskedAndOnlyWhileTheGrantRuns() {
		policy(block -> block.setMaxDaysBack(30));
		LocalDate first = TODAY.minusDays(45);

		corrections.requestBackfill(first, first.plusDays(20), "Nachtrag nach der Elternzeit", member);

		assertThat(notificationsOf(Notification.Type.TIME_BACKFILL_REQUESTED))
				.extracting(Notification::getUserId).containsExactly(admin.getId());
		assertThatThrownBy(() -> corrections.requestBackfill(first, first, "noch einmal", member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.backfillAlreadyRequested");
		// Days beyond the limit are not a lead's to open.
		assertThat(corrections.inbox(0, 20, lead).getContent()).isEmpty();
		TimeCorrectionService.CorrectionRequest asked =
				corrections.inbox(0, 20, admin).getContent().getFirst();
		assertThat(asked.kind()).isEqualTo(TimeCorrectionRequest.Kind.SPAN);
		assertThat(asked.from()).isEqualTo(first);
		assertThat(asked.reason()).isEqualTo(TimePolicy.LockReason.MAX_DAYS_BACK.name());
		assertThatThrownBy(() -> corrections.grant(asked.id(), "ok", lead))
				.isInstanceOf(ApiException.class)
				.extracting(TimePrivacyIntegrationTest::statusOf)
				.isEqualTo(HttpStatus.NOT_FOUND);

		corrections.grant(asked.id(), "Passt, bitte in den nächsten zwei Wochen nachtragen.", admin);

		assertThat(timeTracking.create(typed(first), WorkItem.Source.APP, member).getDate())
				.isEqualTo(first);
		assertThatThrownBy(() -> timeTracking.create(typed(first), WorkItem.Source.APP, lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.dateBeyondLimit");
		TimeCorrectionService.Grant grant = corrections.activeGrants(0, 20, admin).getContent().getFirst();
		assertThat(grant.userId()).isEqualTo(member.getId());
		assertThat(grant.expiresAt()).isEqualTo(NOW.plus(TimeCorrectionService.GRANT_LIFETIME));

		corrections.revokeGrant(grant.id(), admin);

		assertThatThrownBy(() -> timeTracking.create(typed(first.plusDays(1)), WorkItem.Source.APP, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.dateBeyondLimit");
		assertThat(recordsOf(AuditAction.TIME_BACKFILL_REVOKED)).isEqualTo(1);
		// A grant that has run out opens nothing, even before the index removes it.
		grants.save(TimeBackfillGrant.builder().userId(member.getId()).from(first).to(first.plusDays(5))
				.note("abgelaufen").grantedBy(admin.getId()).grantedAt(NOW.minus(Duration.ofDays(15)))
				.expiresAt(NOW.minusSeconds(1)).build());
		assertThatThrownBy(() -> timeTracking.create(typed(first.plusDays(2)), WorkItem.Source.APP, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.dateBeyondLimit");
	}

	@Test
	void daysAreOnlyAskedForWhenOneOfThemNeedsOpening() {
		policy(block -> block.setMaxDaysBack(30));

		assertThatThrownBy(() -> corrections.requestBackfill(TODAY.minusDays(10), TODAY.minusDays(5),
				"schon offen", member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.backfillNotNeeded");
		assertThatThrownBy(() -> corrections.requestBackfill(TODAY.minusDays(200), TODAY.minusDays(40),
				"zu lang", member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodTooLong");
		assertThatThrownBy(() -> corrections.requestBackfill(TODAY.minusDays(40), TODAY.plusDays(1),
				"bis morgen", member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.dateInFuture");
		assertThat(notifications.findAll()).isEmpty();
		assertThat(mongo.findAll(TimeCorrectionRequest.class)).isEmpty();
	}

	@Test
	void requestsMadeBeforeThisStageMoveIntoTheirOwnCollectionOnce() {
		WorkItem frozen = entry(member, TODAY.minusDays(5), 60, "Workshop");
		Map<String, String> meta = new LinkedHashMap<>();
		meta.put("workItem", frozen.getId());
		meta.put("date", frozen.getDate().toString());
		meta.put("project", project.getId());
		meta.put("reason", TimePolicy.LockReason.LOCK_DATE.name());
		meta.put("note", "Es waren 90 Minuten");
		AuditLog before = mongo.insert(AuditLog.builder().action(AuditAction.TIME_CORRECTION_REQUESTED)
				.actorId(member.getId()).timestamp(NOW.minus(Duration.ofDays(1))).metadata(meta).build());
		Document marker = new Document("_id", TimeCorrectionRequestBackfill.MARKER_ID);

		mongo.getCollection("migrations").deleteMany(marker);
		backfill.run(null);
		mongo.getCollection("migrations").deleteMany(marker);
		backfill.run(null);

		assertThat(mongo.findAll(TimeCorrectionRequest.class)).singleElement().satisfies(request -> {
			assertThat(request.getId()).isEqualTo(before.getId());
			assertThat(request.getKind()).isEqualTo(TimeCorrectionRequest.Kind.ENTRY);
			assertThat(request.getWorkItemId()).isEqualTo(frozen.getId());
			assertThat(request.getReason()).isEqualTo(TimePolicy.LockReason.LOCK_DATE);
			assertThat(request.getNote()).isEqualTo("Es waren 90 Minuten");
		});
	}

	// --- access and portability -----------------------------------------------------

	@Test
	void theCsvExportCarriesOnlyTheOwnEntriesWithFormulasNeutralised() throws Exception {
		entry(member, TODAY.minusDays(1), 45, "=HYPERLINK(\"https://evil.example\")");
		entry(lead, TODAY.minusDays(1), 30, "the lead's own afternoon");
		as(member);

		MockHttpServletResponse response = new MockHttpServletResponse();
		export.exportCsv(null, null, response);

		byte[] bytes = response.getContentAsByteArray();
		assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
		String body = new String(bytes, StandardCharsets.UTF_8);
		assertThat(body).contains("\"'=HYPERLINK(\"\"https://evil.example\"\")\"")
				.contains("\"HIN\"")
				.doesNotContain("afternoon");
		assertThat(response.getHeader(TimeExportController.TRUNCATED_HEADER)).isEqualTo("false");
		assertThat(response.getHeader("Content-Disposition")).contains("time-entries-all.csv");
	}

	@Test
	void theCsvExportSpendsTheExportBudget() throws Exception {
		properties.getRateLimit().setExportsPerMinute(2);
		User exporter = user("exporter-" + System.nanoTime(), Role.MEMBER, "en");

		csv.plan(null, null, exporter).close();
		csv.plan(null, null, exporter).close();

		assertThatThrownBy(() -> csv.plan(null, null, exporter))
				.isInstanceOf(ApiException.class)
				.extracting(TimePrivacyIntegrationTest::statusOf)
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void anInstanceBusyWithExportsTurnsTheNextOneAwayWithoutSpendingItsBudget() {
		properties.getRateLimit().setExportsPerMinute(TimeEntryCsvExport.MAX_RUNNING + 1);
		User exporter = user("busy-" + System.nanoTime(), Role.MEMBER, "en");
		List<TimeEntryCsvExport.Plan> running = new ArrayList<>();
		try {
			for (int i = 0; i < TimeEntryCsvExport.MAX_RUNNING; i++) {
				running.add(csv.plan(null, null, exporter));
			}
			assertThatThrownBy(() -> csv.plan(null, null, exporter))
					.isInstanceOf(ApiException.class)
					.hasMessage("error.rateLimited");
		}
		finally {
			running.forEach(TimeEntryCsvExport.Plan::close);
		}
		// Turned away for the instance, not for the person: the last export of the
		// minute is still theirs, and closing a plan twice gives nothing back twice.
		running.forEach(TimeEntryCsvExport.Plan::close);
		try (TimeEntryCsvExport.Plan plan = csv.plan(null, null, exporter)) {
			assertThat(plan.truncated()).isFalse();
		}
	}

	@Test
	void aCsvExportOfDatesNoStoreCanHoldIsRefusedBeforeItCostsAnything() {
		properties.getRateLimit().setExportsPerMinute(1);
		User exporter = user("far-" + System.nanoTime(), Role.MEMBER, "en");

		assertThatThrownBy(() -> csv.plan(LocalDate.of(1, 1, 1), TODAY, exporter))
				.isInstanceOf(ApiException.class)
				.extracting(TimePrivacyIntegrationTest::statusOf)
				.isEqualTo(HttpStatus.BAD_REQUEST);
		// The refusal did not spend the one export this minute allows.
		try (TimeEntryCsvExport.Plan plan = csv.plan(TODAY.minusDays(7), TODAY, exporter)) {
			assertThat(plan.fileName()).isEqualTo("time-entries-" + TODAY.minusDays(7) + "-" + TODAY + ".csv");
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void theAccountExportCarriesTheTimeData() throws Exception {
		policy(block -> block.setLockBefore(TODAY));
		WorkItem frozen = entry(member, TODAY.minusDays(2), 90, "Review");
		approvals.save(TimesheetApproval.builder().userId(member.getId()).projectId(project.getId())
				.periodStart(LocalDate.of(2026, 8, 1)).periodEnd(LocalDate.of(2026, 8, 31))
				.periodType(TimePolicy.ApprovalPeriod.MONTHLY)
				.status(TimesheetApproval.Status.APPROVED).submittedAt(NOW).build());
		corrections.request(frozen.getId(), "Es waren 120 Minuten", member);

		Map<String, Object> data = me.exportData(users.findById(member.getId()).orElseThrow());

		assertThat(data).containsKey("timeTracking");
		Map<String, Object> time = (Map<String, Object>) data.get("timeTracking");
		assertThat((List<?>) time.get("entries")).hasSize(1);
		assertThat((List<?>) time.get("submissions")).hasSize(1);
		assertThat((List<?>) time.get("correctionRequests")).hasSize(1);
		assertThat(time).containsKey("backfillGrants");
		assertThat(time.get("entriesTruncated")).isEqualTo(false);
		byte[] document = pdf.build(users.findById(member.getId()).orElseThrow());
		try (PDDocument parsed = Loader.loadPDF(document)) {
			assertThat(new PDFTextStripper().getText(parsed))
					.contains("Review")
					.contains("Es waren 120 Minuten");
		}
	}

	// --- erasure -------------------------------------------------------------------

	@Test
	void deletingTheAccountKeepsWhatWasSignedOffAndThePseudonymisedEntries() {
		WorkItem kept = entry(member, TODAY.minusDays(40), 60, "Planning");
		TimesheetApproval signedOff = approvals.save(TimesheetApproval.builder().userId(member.getId())
				.projectId(project.getId()).periodStart(LocalDate.of(2026, 7, 1))
				.periodEnd(LocalDate.of(2026, 7, 31)).periodType(TimePolicy.ApprovalPeriod.MONTHLY)
				.status(TimesheetApproval.Status.APPROVED).submittedAt(NOW).build());
		TimesheetApproval pending = approvals.save(TimesheetApproval.builder().userId(member.getId())
				.projectId(project.getId()).periodStart(LocalDate.of(2026, 8, 1))
				.periodEnd(LocalDate.of(2026, 8, 31)).periodType(TimePolicy.ApprovalPeriod.MONTHLY)
				.status(TimesheetApproval.Status.SUBMITTED).submittedAt(NOW).build());
		timers.save(RunningTimer.builder().userId(member.getId()).startedAt(NOW.minusSeconds(600))
				.build());
		policy(block -> block.setMaxDaysBack(30));
		corrections.requestBackfill(TODAY.minusDays(45), TODAY.minusDays(44), "Nachtrag", member);
		corrections.grant(corrections.inbox(0, 20, admin).getContent().getFirst().id(), "Passt.", admin);

		userService.delete(users.findById(member.getId()).orElseThrow());

		assertThat(approvals.findById(signedOff.getId())).isPresent();
		assertThat(approvals.findById(pending.getId())).isEmpty();
		assertThat(timers.findByUserId(member.getId())).isEmpty();
		assertThat(workItems.findById(kept.getId())).get()
				.extracting(WorkItem::getUserId).isEqualTo(member.getId());
		assertThat(departed.findById(member.getId())).isPresent();
		assertThat(mongo.findAll(TimeCorrectionRequest.class)).isEmpty();
		assertThat(grants.findAll()).isEmpty();
	}

	@Test
	void theHistoryForgetsTheNameAndTheWordsOfAnAccountThatIsGone() {
		policy(block -> block.setLockBefore(TODAY));
		User secondAdmin = user("second-admin", Role.ADMIN, "en");
		WorkItem frozen = entry(member, TODAY.minusDays(5), 60, "Workshop");
		WorkItem open = entry(member, TODAY, 60, "Planning");
		timeTracking.update(open.getId(), minutes(45), secondAdmin);
		corrections.request(frozen.getId(), "War an dem Tag krank, bitte streichen", member);
		corrections.answer(corrections.inbox(0, 20, admin).getContent().getFirst().id(),
				"Ich öffne den Tag.", admin);

		userService.delete(users.findById(secondAdmin.getId()).orElseThrow());

		// Whoever changed the entry is no longer named once their account is gone.
		TimeTrackingService.EntryHistory edits = timeTracking.history(open.getId(), 0, 20, member);
		assertThat(edits.rows().getContent()).singleElement()
				.satisfies(log -> assertThat(edits.actorLabelOf(log)).isNull());

		userService.delete(users.findById(member.getId()).orElseThrow());

		// And a conversation with somebody who is gone is not read out to anyone.
		TimeTrackingService.EntryHistory conversation =
				timeTracking.history(frozen.getId(), 0, 20, admin);
		assertThat(conversation.rows().getContent()).isNotEmpty()
				.allSatisfy(log -> assertThat(TimeEntryController.HistoryEntryResponse
						.from(log, conversation.readsConversation(log)).metadata())
						.doesNotContainKeys("note", "reason"));
	}

	// --- entries on an issue ----------------------------------------------------------

	@Test
	void onAnIssueOtherMembersSeeTheHoursButNotWhoWorkedThemOrWhatTheyWrote() {
		Issue issue = issues.save(Issue.builder().projectId(project.getId()).readableId("HIN-1")
				.numberInProject(1).title("Login bug").state("Open").spentMinutes(90)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
		User colleague = user("colleague", Role.MEMBER, "de");
		workItems.save(WorkItem.builder().issueId(issue.getId()).projectId(project.getId())
				.userId(member.getId()).date(TODAY.minusDays(1)).durationMinutes(90)
				.activityType("Meeting").description("Arzttermin nachgeholt")
				.startedAt(NOW.minusSeconds(7200)).endedAt(NOW.minusSeconds(1800))
				.tags(List.of("privat")).source(WorkItem.Source.APP).build());

		as(colleague);
		assertThat(workItemsApi.list(issue.getId())).singleElement().satisfies(row -> {
			assertThat(row.hidden()).isTrue();
			assertThat(row.durationMinutes()).isEqualTo(90);
			assertThat(row.date()).isEqualTo(TODAY.minusDays(1));
			assertThat(row.activityType()).isEqualTo("Meeting");
			assertThat(row.userId()).isNull();
			assertThat(row.description()).isNull();
			assertThat(row.createdAt()).isNull();
			assertThat(row.startedAt()).isNull();
			assertThat(row.endedAt()).isNull();
			assertThat(row.tags()).isEmpty();
		});
		// A lead reads them while the policy says so, and only then.
		as(lead);
		assertThat(workItemsApi.page(issue.getId(), 0, 20).getContent().getFirst().hidden()).isTrue();
		policy(block -> block.setLeadsSeeMemberEntries(true));
		assertThat(workItemsApi.list(issue.getId()).getFirst().userId()).isEqualTo(member.getId());
		// The owner and an administrator read them either way.
		policy(block -> block.setLeadsSeeMemberEntries(false));
		as(member);
		assertThat(workItemsApi.list(issue.getId()).getFirst().description())
				.isEqualTo("Arzttermin nachgeholt");
		as(admin);
		assertThat(workItemsApi.page(issue.getId(), 0, 20).getContent().getFirst().hidden()).isFalse();
		// With the module off, the list the published app reads stays as it was.
		policy(block -> block.setAdvancedEnabled(false));
		as(colleague);
		assertThat(workItemsApi.list(issue.getId()).getFirst()).satisfies(row -> {
			assertThat(row.hidden()).isFalse();
			assertThat(row.userId()).isEqualTo(member.getId());
		});
	}

	@Test
	void aLeadChangesAMembersEntryOnlyWhileLeadsMayReadTheirEntries() {
		WorkItem members = entry(member, TODAY.minusDays(2), 60, "Review");

		assertThatThrownBy(() -> timeTracking.update(members.getId(), minutes(30), lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.editOwnOnly");
		assertThatThrownBy(() -> timeTracking.delete(members.getId(), lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.deleteOwnOnly");

		policy(block -> block.setLeadsSeeMemberEntries(true));

		assertThat(timeTracking.update(members.getId(), minutes(30), lead).getDurationMinutes())
				.isEqualTo(30);
		assertThat(recordsOf(AuditAction.TIME_ENTRY_UPDATED)).isEqualTo(1);
	}

	// --- the last reader of leadsSeeMemberEntries ------------------------------------

	@Test
	void aLeadSeesMemberRowsOnlyWhileThePolicyIsOnAndOnlyForAProjectTheyLead() {
		entry(member, TODAY.minusDays(3), 120, "Sprint");
		Project other = projects.save(Project.builder().key("OPS").name("Operations")
				.leadIds(new ArrayList<>())
				.memberIds(new ArrayList<>(List.of(member.getId(), lead.getId()))).build());
		workItems.save(WorkItem.builder().userId(member.getId()).projectId(other.getId())
				.date(TODAY.minusDays(3)).durationMinutes(60).tags(List.of())
				.source(WorkItem.Source.APP).build());
		LocalDate from = TODAY.minusDays(10);

		assertThat(timeTracking.timesheetPage(from, TODAY, null, project.getId(), 0, 50, lead)
				.getContent()).isEmpty();

		policy(block -> block.setLeadsSeeMemberEntries(true));

		assertThat(timeTracking.timesheetPage(from, TODAY, null, project.getId(), 0, 50, lead)
				.getContent()).extracting(TimeTrackingService.TimesheetRow::userId)
				.containsExactly(member.getId());
		// Never the whole instance, and never on the route the published app reads.
		assertThat(timeTracking.timesheetPage(from, TODAY, null, null, 0, 50, lead).getContent())
				.isEmpty();
		assertThat(timeTracking.timesheet(from, TODAY, null, project.getId(), lead)).isEmpty();
		// Nor a project they only belong to: there they see their own rows, and asking
		// for a member's is refused.
		assertThat(timeTracking.timesheetPage(from, TODAY, null, other.getId(), 0, 50, lead)
				.getContent()).isEmpty();
		assertThatThrownBy(() -> timeTracking.timesheetPage(from, TODAY, member.getId(),
				other.getId(), 0, 50, lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.accessDenied");
		// An administrator sees the row either way; a member who leads nothing still
		// cannot ask for somebody else's.
		assertThat(timeTracking.timesheetPage(from, TODAY, null, project.getId(), 0, 50, admin)
				.getContent()).hasSize(1);
		assertThatThrownBy(() -> timeTracking.timesheetPage(from, TODAY, lead.getId(),
				project.getId(), 0, 50, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.accessDenied");
		// Reading a timesheet is nothing the audit log is told about: nobody learns
		// who looked at whose rows by watching the log fill up.
		assertThat(mongo.count(new Query(), AuditLog.class)).isZero();
	}
}
