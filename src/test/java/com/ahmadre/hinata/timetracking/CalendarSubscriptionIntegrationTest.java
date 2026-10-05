package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.ics.IcsFetchError;
import com.ahmadre.hinata.ics.IcsFetchResult;
import com.ahmadre.hinata.ics.IcsFetcher;
import com.ahmadre.hinata.ics.IcsUrlCipher;
import com.ahmadre.hinata.me.MeService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Calendar subscriptions (HIN-94) against a real database: the address kept as a secret, the
 * window replaced by a diff, the cap, the takeover by hand and by rule, who reaches whose events,
 * the organisation's switch, and what an account deletion and a data export do.
 *
 * <p>The fetcher is spied and handed the feeds, and the asynchronous start is held, so each test
 * drives {@link CalendarSync#claim} and {@link CalendarSync#run} itself and reads a settled state.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true",
		// 32 bytes, so subscription addresses can be stored.
		"hinata.ics.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Import(CalendarSubscriptionIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class CalendarSubscriptionIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

	/** What a calendar service hands out: the token is the secret part. */
	private static final String TOKEN = "s3cr3tT0kenAbc123";
	private static final String FEED = "https://calendar.example.org/private-" + TOKEN + "/basic.ics";

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
	private UserService userService;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private MeService me;
	@Autowired
	private CalendarSubscriptionService subscriptions;
	@Autowired
	private CalendarFetchJob job;
	@MockitoSpyBean
	private CalendarSync sync;
	@MockitoSpyBean
	private IcsFetcher fetcher;
	@MockitoSpyBean
	private IcsUrlCipher cipher;

	private User owner;
	private User stranger;
	private Project project;

	@BeforeEach
	void seed() {
		for (String collection : List.of("projects", "users", "work_items", "calendar_subscriptions",
				"calendar_events", "audit_log", "server_settings", "time_tags")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		reset(sync, fetcher, cipher);
		// Held: each test reads the feed itself, on its own thread.
		doReturn(true).when(sync).start(anyString(), anyBoolean(), any());
		policy(true, false);
		owner = user("owner");
		stranger = user("stranger");
		project = projects.save(Project.builder().key("HIN").name("Hinata").leadId(owner.getId())
				.leadIds(new ArrayList<>(List.of(owner.getId())))
				.memberIds(new ArrayList<>(List.of(owner.getId()))).build());
	}

	private User user(String name) {
		return users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.roles(Set.of(Role.MEMBER)).active(true).timezone("UTC").locale("en").build());
	}

	private void policy(boolean icsImport, boolean tagRequired) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setIcsImportEnabled(icsImport);
		if (tagRequired) {
			ServerSettings.TimeTracking.RequiredFields required = new ServerSettings.TimeTracking.RequiredFields();
			required.setTag(true);
			block.setRequiredFields(required);
		}
		current.setTimeTracking(block);
		settings.save(current);
	}

	private CalendarSubscription subscribe(User as) {
		return subscriptions.create(as, new CalendarSubscriptionService.Draft("Work", FEED, "#3366cc", true, null));
	}

	private void serve(String ics) {
		doReturn(CompletableFuture.completedFuture(new IcsFetchResult(IcsFetchResult.Outcome.FETCHED,
				ics.getBytes(StandardCharsets.UTF_8), null, null, null, 200)))
				.when(fetcher).fetch(any(), any(), any());
	}

	private void serveFailure(IcsFetchError error) {
		doReturn(CompletableFuture.completedFuture(new IcsFetchResult(IcsFetchResult.Outcome.FAILED,
				null, null, null, error, 0)))
				.when(fetcher).fetch(any(), any(), any());
	}

	/** One fetch, start to outcome, on this thread. */
	private CalendarSubscription read(CalendarSubscription subscription) {
		CalendarSubscription claimed = sync.claim(subscription.getId(), false, Duration.ZERO);
		assertThat(claimed).as("the subscription is free to claim").isNotNull();
		sync.run(claimed);
		return mongo.findById(subscription.getId(), CalendarSubscription.class);
	}

	private List<CalendarEvent> eventsOf(CalendarSubscription subscription) {
		return mongo.find(Query.query(Criteria.where("subscriptionId").is(subscription.getId())), CalendarEvent.class);
	}

	private static String calendar(String... events) {
		return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + String.join("", events)
				+ "END:VCALENDAR\r\n";
	}

	private static String event(String uid, String start, String end, String summary, String... extra) {
		return "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20260901T000000Z\r\nDTSTART:" + start + "\r\nDTEND:" + end
				+ "\r\nSUMMARY:" + summary + "\r\n" + String.join("", extra) + "END:VEVENT\r\n";
	}

	/** A weekly series over the window with one occurrence moved and one dropped. */
	private static String series() {
		return calendar(
				event("weekly", "20260901T090000Z", "20260901T100000Z", "Standup",
						"RRULE:FREQ=WEEKLY;COUNT=6\r\n", "EXDATE:20260915T090000Z\r\n"),
				"BEGIN:VEVENT\r\nUID:weekly\r\nDTSTAMP:20260901T000000Z\r\nRECURRENCE-ID:20260908T090000Z\r\n"
						+ "DTSTART:20260908T140000Z\r\nDTEND:20260908T150000Z\r\nSUMMARY:Standup (moved)\r\nEND:VEVENT\r\n",
				event("review", "20260904T130000Z", "20260904T143000Z", "Review"));
	}

	// --- the address ------------------------------------------------------------------------

	@Test
	void withoutTheSecret_subscribingIs409() {
		doReturn(false).when(cipher).isConfigured();

		assertThatThrownBy(() -> subscribe(owner))
				.isInstanceOfSatisfying(ApiException.class, refused -> {
					assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(refused.getMessageKey()).isEqualTo("error.ics.notConfigured");
				});
		assertThat(mongo.count(new Query(), CalendarSubscription.class)).isZero();
	}

	@Test
	void theAddressIsStoredEncrypted_answeredAsAHost_andNeverLogged(CapturedOutput output) {
		serveFailure(IcsFetchError.REDIRECT);

		CalendarSubscription created = subscribe(owner);
		read(created);

		Document stored = mongo.getCollection("calendar_subscriptions").find().first();
		assertThat(stored.toJson()).doesNotContain(TOKEN).doesNotContain("calendar.example.org/private");
		assertThat(stored.getString("encryptedUrl")).startsWith("v1:");
		CalendarSubscriptionController.SubscriptionResponse answered =
				CalendarSubscriptionController.SubscriptionResponse.from(created);
		assertThat(answered.hostMasked()).isEqualTo("calendar.example.org/…");
		assertThat(mongo.find(new Query(), AuditLog.class)).extracting(log -> log.toString()).noneMatch(
				line -> line.contains(TOKEN));
		assertThat(output.getAll()).doesNotContain(TOKEN);
	}

	@Test
	void aLoopbackAddressIsRefusedBeforeAnyFetch() {
		assertThatThrownBy(() -> subscriptions.create(owner,
				new CalendarSubscriptionService.Draft("Local", "https://127.0.0.1/feed.ics", null, true, null)))
				.hasMessage("error.ics.hostNotAllowed");
		verify(fetcher, never()).fetch(any(), any(), any());
	}

	@Test
	void aRedirectingFeedFails_andFiveInARowPause() {
		serveFailure(IcsFetchError.REDIRECT);
		CalendarSubscription created = subscribe(owner);

		CalendarSubscription once = read(created);
		assertThat(once.getLastStatus()).isEqualTo(CalendarSubscription.Status.FAILED);
		assertThat(once.getLastError()).isEqualTo("error.ics.redirect");

		for (int attempt = 1; attempt < CalendarSubscription.PAUSE_AFTER_FAILURES; attempt++) {
			read(created);
		}
		CalendarSubscription paused = mongo.findById(created.getId(), CalendarSubscription.class);
		assertThat(paused.getLastStatus()).isEqualTo(CalendarSubscription.Status.PAUSED);
		assertThat(sync.claim(created.getId(), true, Duration.ZERO)).as("the schedule leaves it alone").isNull();
	}

	// --- the window -------------------------------------------------------------------------

	@Test
	void readingTwiceKeepsTheSameEvents_andASeriesKeepsItsExceptions() {
		serve(series());
		CalendarSubscription created = subscribe(owner);

		CalendarSubscription first = read(created);
		List<CalendarEvent> before = eventsOf(created);
		CalendarSubscription second = read(created);
		List<CalendarEvent> after = eventsOf(created);

		assertThat(first.getLastStatus()).isEqualTo(CalendarSubscription.Status.OK);
		assertThat(second.getEventCount()).isEqualTo(first.getEventCount());
		// Six weekly occurrences, one dropped, plus the single review.
		assertThat(after).hasSize(6);
		assertThat(after).usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrderElementsOf(before);
		CalendarEvent moved = after.stream().filter(event -> "20260908T090000Z".equals(event.getRecurrenceId()))
				.findFirst().orElseThrow();
		assertThat(moved.getStartsAt()).isEqualTo(Instant.parse("2026-09-08T14:00:00Z"));
		assertThat(moved.getSummary()).isEqualTo("Standup (moved)");
		assertThat(after).noneMatch(event -> "20260915T090000Z".equals(event.getRecurrenceId()));
	}

	@Test
	void anEventThatLeftTheFeedLeavesTheWindow() {
		serve(series());
		CalendarSubscription created = subscribe(owner);
		read(created);

		serve(calendar(event("review", "20260904T130000Z", "20260904T143000Z", "Review")));
		read(created);

		assertThat(eventsOf(created)).extracting(CalendarEvent::getUid).containsExactly("review");
	}

	@Test
	void moreThanFiveThousandEventsAreCut_andSaySo() {
		// Sixty daily series of about ninety occurrences each inside the window: more than is kept.
		StringBuilder many = new StringBuilder();
		for (int i = 0; i < 60; i++) {
			String minute = String.format("%02d", i);
			many.append(event("s" + i, "20260910T08" + minute + "00Z", "20260910T09" + minute + "00Z",
					"Slot " + i, "RRULE:FREQ=DAILY;COUNT=120\r\n"));
		}
		serve(calendar(many.toString()));
		CalendarSubscription created = subscribe(owner);

		CalendarSubscription read = read(created);

		assertThat(read.getLastStatus()).as(read.getLastError()).isEqualTo(CalendarSubscription.Status.TRUNCATED);
		assertThat(read.getLastError()).isEqualTo("error.calendar.truncated");
		assertThat(mongo.count(Query.query(Criteria.where("subscriptionId").is(created.getId())), CalendarEvent.class))
				.isEqualTo(CalendarEvent.PER_SUBSCRIPTION_MAX);
	}

	@Test
	void twoClaimsOfOneSubscription_onlyOneWins() {
		CalendarSubscription created = subscribe(owner);

		assertThat(sync.claim(created.getId(), false, Duration.ZERO)).isNotNull();
		assertThat(sync.claim(created.getId(), false, Duration.ZERO)).isNull();
	}

	// --- takeover ---------------------------------------------------------------------------

	@Test
	void takingAnEventOverTwiceFilesOneEntry() {
		serve(series());
		CalendarSubscription created = subscribe(owner);
		read(created);
		CalendarEvent review = eventsOf(created).stream().filter(event -> event.getUid().equals("review"))
				.findFirst().orElseThrow();
		CalendarSubscriptionService.Conversion choice = new CalendarSubscriptionService.Conversion(project.getId(),
				null, List.of(), null, null);

		WorkItem first = subscriptions.convert(owner, review.getId(), choice);
		WorkItem second = subscriptions.convert(owner, review.getId(), choice);

		assertThat(second.getId()).isEqualTo(first.getId());
		assertThat(first.getSource()).isEqualTo(WorkItem.Source.CALENDAR);
		assertThat(first.getDurationMinutes()).isEqualTo(90);
		assertThat(first.getDescription()).isEqualTo("Review");
		assertThat(mongo.count(new Query(), WorkItem.class)).isOne();
		assertThat(subscriptions.layer(owner, LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4)))
				.singleElement().extracting(CalendarSubscriptionService.LayerEvent::convertedEntryId)
				.isEqualTo(first.getId());
	}

	@Test
	void theRuleTakesOverOnlyEndedEventsSinceItWasSwitchedOn_andRecordsWhatItSkips() {
		policy(true, true);
		serve(calendar(
				event("before", "20260907T080000Z", "20260907T090000Z", "Before the rule"),
				event("ended", "20260907T100000Z", "20260907T110000Z", "Ended"),
				event("later", "20260907T150000Z", "20260907T160000Z", "Not yet")));
		CalendarSubscription created = subscribe(owner);
		subscriptions.update(owner, created.getId(), new CalendarSubscriptionService.Patch(null, null, null, null,
				new CalendarSubscriptionService.RuleDraft(true, project.getId(), List.of(), null)));
		mongo.updateFirst(Query.query(Criteria.where("_id").is(created.getId())),
				new org.springframework.data.mongodb.core.query.Update().set("autoConvert.since",
						Instant.parse("2026-09-07T09:30:00Z")), CalendarSubscription.class);

		CalendarSubscription read = read(created);

		// A tag is required and the rule names none: refused, kept for the owner, not filed.
		assertThat(mongo.count(new Query(), WorkItem.class)).isZero();
		assertThat(read.getSkips()).singleElement().satisfies(skip ->
				assertThat(skip.messageKey()).isEqualTo("error.time.required.tag"));

		policy(true, false);
		mongo.updateMulti(new Query(), new org.springframework.data.mongodb.core.query.Update().unset("autoHandledAt"),
				CalendarEvent.class);
		read(created);
		assertThat(mongo.findAll(WorkItem.class)).singleElement().satisfies(entry -> {
			assertThat(entry.getDescription()).isEqualTo("Ended");
			assertThat(entry.getProjectId()).isEqualTo(project.getId());
		});
		// Read again: the occurrence was dealt with, nothing new is filed.
		read(created);
		assertThat(mongo.count(new Query(), WorkItem.class)).isOne();
	}

	// --- who sees what ----------------------------------------------------------------------

	@Test
	void somebodyElsesSubscriptionsAndEventsAreNotFound() {
		serve(series());
		CalendarSubscription created = subscribe(owner);
		read(created);
		String eventId = eventsOf(created).getFirst().getId();

		assertThatThrownBy(() -> subscriptions.require(stranger, created.getId()))
				.hasMessage("error.notFound");
		assertThatThrownBy(() -> subscriptions.convert(stranger, eventId,
				new CalendarSubscriptionService.Conversion(null, null, null, null, null)))
				.hasMessage("error.notFound");
		assertThatThrownBy(() -> subscriptions.delete(stranger, created.getId())).hasMessage("error.notFound");
		assertThat(subscriptions.list(stranger)).isEmpty();
		assertThat(subscriptions.layer(stranger, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEmpty();
		assertThat(String.valueOf(me.exportData(stranger))).doesNotContain("Standup");
	}

	@Test
	void withTheImportSwitchedOff_everythingIsGoneAndTheJobDoesNothing() throws Exception {
		serve(series());
		CalendarSubscription created = subscribe(owner);
		read(created);
		policy(false, false);

		assertThatThrownBy(() -> subscriptions.list(owner)).isInstanceOfSatisfying(ApiException.class,
				refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
		assertThatThrownBy(() -> subscriptions.refresh(owner, created.getId())).isInstanceOf(ApiException.class);
		assertThat(subscriptions.layer(owner, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEmpty();
		assertThat(job.run()).isZero();
	}

	@Test
	void refreshingIsAllowedOnceAMinute() {
		CalendarSubscription created = subscribe(owner);

		subscriptions.refresh(owner, created.getId());

		assertThatThrownBy(() -> subscriptions.refresh(owner, created.getId()))
				.isInstanceOfSatisfying(ApiException.class, refused -> {
					assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
					assertThat(refused.getMessageKey()).isEqualTo("error.calendar.refreshTooSoon");
				});
	}

	// --- personal data ----------------------------------------------------------------------

	@Test
	void theExportHoldsTheSubscriptionWithoutItsAddress_andDeletionRemovesEverything() {
		serve(series());
		CalendarSubscription created = subscribe(owner);
		read(created);

		@SuppressWarnings("unchecked")
		Map<String, Object> exported = (Map<String, Object>) me.exportData(owner).get("calendarSubscriptions");
		String json = String.valueOf(exported);
		assertThat(json).contains("calendar.example.org").contains("Standup").doesNotContain(TOKEN)
				.doesNotContain("v1:");

		userService.delete(users.findById(owner.getId()).orElseThrow());

		assertThat(mongo.count(new Query(), CalendarSubscription.class)).isZero();
		assertThat(mongo.count(new Query(), CalendarEvent.class)).isZero();
		assertThat(mongo.count(Query.query(Criteria.where("action")
				.is(AuditAction.TIME_CALENDAR_SUBSCRIPTION_CREATED)), AuditLog.class)).isOne();
	}
}
