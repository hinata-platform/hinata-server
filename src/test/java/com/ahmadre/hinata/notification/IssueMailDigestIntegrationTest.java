package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.mongodb.MongoWriteException;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The bundled change mail, end to end against a real MongoDB and a clock the
 * test moves by hand.
 *
 * <p>Every rule here is about <em>time</em> or about <em>exactly once</em>, and
 * neither survives being mocked: the debounce is a query over two timestamps,
 * "one bundle per recipient and issue" is a unique partial index, and "one mail
 * with two instances running" is a conditional {@code findAndModify}. Time is
 * moved rather than waited out — a test that sleeps for 45 minutes is a test
 * nobody runs.
 *
 * <p>The windows are deliberately set to values that are <em>not</em> the
 * defaults, so a hard-coded constant sneaking back in would fail here.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		// Not 5m/30m: these must be read from configuration, not from a constant.
		"hinata.notification.watch.quiet-window=2m",
		"hinata.notification.watch.max-delay=10m"
})
@Import(IssueMailDigestIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class IssueMailDigestIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	/** Where the test's "now" lives, so the windows can be crossed instantly. */
	static final AtomicReference<Instant> NOW =
			new AtomicReference<>(Instant.parse("2026-08-18T09:00:00Z"));

	/**
	 * Replaces the application's {@code Clock} with one the test winds forward.
	 * {@code @Primary} rather than a replacement definition so the production
	 * {@code ClockConfig} stays exactly as it ships.
	 */
	@TestConfiguration
	static class FrozenClock {
		@Bean
		@Primary
		Clock testClock() {
			return new Clock() {
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
					return NOW.get();
				}
			};
		}
	}

	/** No SMTP anywhere near a test; the mock is also how sends are counted. */
	@MockitoBean
	private MailService mail;

	/** Its @Scheduled sweep would race the ones the tests drive by hand. */
	@MockitoBean
	private IssueDigestJob job;

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private IssueDigestService digests;
	@Autowired
	private IssueMailDigestRepository repository;
	@Autowired
	private IssueRepository issues;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;

	private User watcher;
	private Project project;
	private Issue issue;

	@BeforeEach
	void seed() {
		NOW.set(Instant.parse("2026-08-18T09:00:00Z"));
		for (String collection : List.of("issues", "projects", "users", "issue_mail_digests")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		watcher = users.save(User.builder().email("watcher@example.org").username("watcher")
				.displayName("Watcher").roles(Set.of(Role.MEMBER)).active(true).locale("en")
				.notificationPreferences(NotificationPreferences.defaults()).build());
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(watcher.getId()).leadIds(new ArrayList<>(List.of(watcher.getId())))
				.memberIds(new ArrayList<>(List.of(watcher.getId()))).build());
		issue = issues.save(Issue.builder().projectId(project.getId()).readableId("HIN-42")
				.numberInProject(42).title("Login bug").state("Open")
				.watcherIds(new ArrayList<>(List.of(watcher.getId())))
				.assigneeIds(new ArrayList<>()).tags(new ArrayList<>())
				.dependsOnIds(new ArrayList<>()).build());
	}

	// --- helpers ---------------------------------------------------------------

	private void advance(Duration by) {
		NOW.updateAndGet(now -> now.plus(by));
	}

	private void queueStateChange(String from, String to) {
		digests.queue(issue, watcher,
				List.of(new FieldChange(IssueChangeDiff.STATE, from, to)));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturedModel() {
		ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
		verify(mail).sendTemplate(anyString(), anyString(), eq("email/issue-changes"),
				model.capture());
		return model.getValue();
	}

	private String capturedSubject() {
		ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
		verify(mail).sendTemplate(anyString(), subject.capture(), anyString(), any());
		return subject.getValue();
	}

	@SuppressWarnings("unchecked")
	private List<IssueChangeRenderer.Line> capturedLines() {
		return (List<IssueChangeRenderer.Line>) capturedModel().get("lines");
	}

	// --- bundling --------------------------------------------------------------

	@Test
	void fiveChangesWithinTwoMinutesBecomeOneMailWithFiveLines() {
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.STATE, "Open", "In Progress")));
		advance(Duration.ofSeconds(20));
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR")));
		advance(Duration.ofSeconds(20));
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.DUE_DATE, null, "2026-08-23")));
		advance(Duration.ofSeconds(20));
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.TAGS, null, "ui")));
		advance(Duration.ofSeconds(20));
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.DESCRIPTION, null, null)));

		assertThat(repository.findAll()).as("one bundle, not five").hasSize(1);
		// Nothing is due yet: the quiet window has not elapsed since the last edit.
		assertThat(digests.sweep(50)).isZero();

		advance(Duration.ofMinutes(3));
		assertThat(digests.sweep(50)).isEqualTo(1);

		assertThat(capturedLines()).hasSize(5);
		verify(mail).sendTemplate(eq("watcher@example.org"), anyString(),
				eq("email/issue-changes"), any());
	}

	/**
	 * Continuous editing must not starve the debounce: every edit pushes the quiet
	 * window out, so without the ceiling this mail would never be sent.
	 */
	@Test
	void continuousEditingStillSendsOnceTheCeilingIsReached() {
		for (int minute = 0; minute < 45; minute++) {
			queueStateChange("S" + minute, "S" + (minute + 1));
			advance(Duration.ofMinutes(1));
			if (digests.sweep(50) > 0) {
				assertThat(minute)
						.as("max-delay is 10 minutes, so it cannot take longer than that")
						.isLessThanOrEqualTo(10);
				verify(mail).sendTemplate(anyString(), anyString(), eq("email/issue-changes"), any());
				return;
			}
		}
		throw new AssertionError("continuous editing never produced a mail");
	}

	@Test
	void nothingIsSentBeforeTheQuietWindowElapses() {
		queueStateChange("Open", "Done");

		advance(Duration.ofSeconds(115));
		assertThat(digests.sweep(50)).isZero();

		advance(Duration.ofSeconds(10));
		assertThat(digests.sweep(50)).isEqualTo(1);
	}

	/** The queue holds raw values precisely so this can be decided at send time. */
	@Test
	void aFieldChangedAndChangedBackProducesNoMail() {
		queueStateChange("Open", "In Progress");
		advance(Duration.ofSeconds(30));
		queueStateChange("In Progress", "Open");

		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isZero();
		verify(mail, never()).sendTemplate(anyString(), anyString(), anyString(), any());
		assertThat(repository.findAll()).singleElement()
				.satisfies(entry -> assertThat(entry.getSentAt())
						.as("consumed, so it is never reconsidered").isNotNull());
	}

	@Test
	void repeatedChangesToOneFieldCollapseToOneLine() {
		queueStateChange("Open", "In Progress");
		advance(Duration.ofSeconds(30));
		queueStateChange("In Progress", "Done");
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isEqualTo(1);

		assertThat(capturedLines()).singleElement()
				.satisfies(line -> assertThat(line.value()).isEqualTo("Open → Done"));
	}

	// --- exactly once ----------------------------------------------------------

	/**
	 * The bundling is a database constraint, not a service convention: two
	 * concurrent edits racing to open a bundle must not each get one.
	 */
	@Test
	void theUniqueIndexRefusesASecondOpenBundleForTheSameRecipientAndIssue() {
		queueStateChange("Open", "Done");

		assertThatThrownBy(() -> mongo.insert(IssueMailDigest.builder()
				.userId(watcher.getId()).issueId(issue.getId()).projectId(project.getId())
				.firstQueuedAt(NOW.get()).lastQueuedAt(NOW.get()).build()))
				.isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
				.hasCauseInstanceOf(MongoWriteException.class);
	}

	/** Once sent, the row leaves the partial index, so the next change opens a
	 *  fresh bundle rather than being appended to a mail already gone out. */
	@Test
	void aChangeAfterTheMailOpensAFreshBundle() {
		queueStateChange("Open", "In Progress");
		advance(Duration.ofMinutes(3));
		assertThat(digests.sweep(50)).isEqualTo(1);

		queueStateChange("In Progress", "Done");

		assertThat(repository.findAll()).hasSize(2);
		assertThat(repository.findByUserIdAndIssueIdAndSentAtIsNull(
				watcher.getId(), issue.getId())).hasSize(1);
	}

	/**
	 * Two instances sweeping the same minute. The conditional claim on
	 * {@code sentAt == null} is what makes exactly one of them the sender.
	 */
	@Test
	void twoInstancesSweepingTogetherSendExactlyOneMail() throws Exception {
		queueStateChange("Open", "Done");
		advance(Duration.ofMinutes(3));

		AtomicInteger sent = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			for (int i = 0; i < 4; i++) {
				pool.submit(() -> {
					start.await();
					sent.addAndGet(digests.sweep(50));
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(sent.get()).isEqualTo(1);
		verify(mail).sendTemplate(anyString(), anyString(), eq("email/issue-changes"), any());
	}

	/**
	 * The claim is written before the mail is handed over, so an instance that dies
	 * in between loses a mail rather than repeating it. Nothing here simulates the
	 * crash better than simply sweeping again: the row is claimed, and the second
	 * sweep must find nothing to do with it.
	 */
	@Test
	void aSecondSweepAfterTheClaimNeverRepeatsTheMail() {
		queueStateChange("Open", "Done");
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isEqualTo(1);
		advance(Duration.ofMinutes(30));
		assertThat(digests.sweep(50)).as("nothing is due a second time").isZero();

		verify(mail).sendTemplate(anyString(), anyString(), eq("email/issue-changes"), any());
	}

	// --- everything is re-checked at send time ---------------------------------

	/**
	 * The mail is composed up to ten minutes after the change. If access is gone
	 * by then the mail must not go out — and the bundle must be consumed, not
	 * retried every minute forever.
	 */
	@Test
	void accessLostBetweenQueueingAndSendingDiscardsTheMail() {
		queueStateChange("Open", "Done");
		project.getMemberIds().remove(watcher.getId());
		project.setLeadIds(new ArrayList<>());
		project.setLeadId(null);
		projects.save(project);
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isZero();

		verify(mail, never()).sendTemplate(anyString(), anyString(), anyString(), any());
		assertThat(repository.findByUserIdAndIssueIdAndSentAtIsNull(
				watcher.getId(), issue.getId())).isEmpty();
	}

	@Test
	void watchingSwitchedOffBetweenQueueingAndSendingStopsTheMail() {
		queueStateChange("Open", "Done");
		NotificationPreferences off = NotificationPreferences.defaults();
		off.getEvents().get(NotificationPreferences.WATCHING).setEmail(false);
		watcher.setNotificationPreferences(off);
		users.save(watcher);
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isZero();

		verify(mail, never()).sendTemplate(anyString(), anyString(), anyString(), any());
	}

	@Test
	void anIssueDeletedMidFlightIsSkippedWithoutFailingTheSweep() {
		queueStateChange("Open", "Done");
		issues.deleteById(issue.getId());
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isZero();

		verify(mail, never()).sendTemplate(anyString(), anyString(), anyString(), any());
	}

	/**
	 * Archiving is not a reason to swallow the mail — it is the single change a
	 * watcher most needs to hear about, because the issue they subscribed to has
	 * just vanished from every list they look at. The issue still exists and the
	 * CTA still resolves, so the mail goes out.
	 */
	@Test
	void anIssueArchivedMidFlightIsStillMailed() {
		queueStateChange("Open", "Done");
		issue.setArchived(true);
		issues.save(issue);
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isEqualTo(1);

		verify(mail).sendTemplate(eq("watcher@example.org"), anyString(),
				eq("email/issue-changes"), any());
	}

	/**
	 * The regression the old "drop anything archived" rule hid: an archival at the
	 * end of a burst took every edit queued before it down with it. Bob moves the
	 * due date, re-prioritises, and then archives — the watcher hears about all
	 * three, in one mail, or the bundle was a black hole.
	 */
	@Test
	void anArchivalAfterOtherEditsCarriesAllOfThemInOneMail() {
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.DUE_DATE, null, "2026-08-23")));
		advance(Duration.ofSeconds(20));
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR")));
		advance(Duration.ofSeconds(20));
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.ARCHIVED, "false", "true")));
		issue.setArchived(true);
		issues.save(issue);
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isEqualTo(1);

		assertThat(capturedLines()).as("the two edits AND the archival").hasSize(3);
	}

	// --- the issue moved out from under the queued mail ------------------------

	/**
	 * The leak the queue-time {@code projectId} allowed. The bundle records the
	 * project the issue was in when the change happened; the body is rendered from
	 * the issue as it is now. Move it in between and the access check was
	 * validating the wrong project entirely — mailing a watcher of project A the
	 * current title, and the current key, of an issue that now lives in B.
	 */
	@Test
	void anIssueMovedToAProjectTheWatcherCannotSeeIsNeverMailed() {
		queueStateChange("Open", "Done");
		Project elsewhere = projects.save(Project.builder().key("OTH").name("Other")
				.memberIds(new ArrayList<>()).leadIds(new ArrayList<>()).build());
		issue.setProjectId(elsewhere.getId());
		issue.setReadableId("OTH-19");
		issues.save(issue);
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isZero();

		verify(mail, never()).sendTemplate(anyString(), anyString(), anyString(), any());
	}

	/**
	 * And the belt-and-braces half: even where the recipient can see both projects,
	 * a bundle whose stored project no longer matches the issue's is stale by
	 * definition. It describes changes made somewhere the issue no longer is.
	 */
	@Test
	void anIssueMovedMidFlightIsDroppedEvenWhenTheWatcherSeesBothProjects() {
		queueStateChange("Open", "Done");
		Project elsewhere = projects.save(Project.builder().key("OTH").name("Other")
				.leadId(watcher.getId()).leadIds(new ArrayList<>(List.of(watcher.getId())))
				.memberIds(new ArrayList<>(List.of(watcher.getId()))).build());
		issue.setProjectId(elsewhere.getId());
		issue.setReadableId("OTH-19");
		issues.save(issue);
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isZero();

		verify(mail, never()).sendTemplate(anyString(), anyString(), anyString(), any());
	}

	/** One bad row must not stop the sweep for everybody else. */
	@Test
	void oneUnusableEntryDoesNotStopTheOthers() {
		queueStateChange("Open", "Done");
		// A queued bundle whose recipient no longer exists.
		mongo.insert(IssueMailDigest.builder()
				.userId("ghost").issueId(issue.getId()).projectId(project.getId())
				.changes(new ArrayList<>(List.of(
						new FieldChange(IssueChangeDiff.STATE, "Open", "Done"))))
				.firstQueuedAt(NOW.get()).lastQueuedAt(NOW.get())
				.softDueAt(NOW.get().plus(Duration.ofMinutes(2)))
				.hardDueAt(NOW.get().plus(Duration.ofMinutes(10))).build());
		advance(Duration.ofMinutes(3));

		assertThat(digests.sweep(50)).isEqualTo(1);
	}

	// --- content ---------------------------------------------------------------

	@Test
	void theSubjectCarriesTheIssueKeyAndTheChangeCount() {
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.STATE, "Open", "Done"),
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"),
				new FieldChange(IssueChangeDiff.DUE_DATE, null, "2026-08-23")));
		advance(Duration.ofMinutes(3));

		digests.sweep(50);

		assertThat(capturedSubject()).startsWith("[Hinata] HIN-42:").contains("3");
	}

	@Test
	void aGermanWatcherGetsAGermanSubjectAndGermanLines() {
		watcher.setLocale("de");
		users.save(watcher);
		digests.queue(issue, watcher, List.of(
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"),
				new FieldChange(IssueChangeDiff.DUE_DATE, null, "2026-08-23")));
		advance(Duration.ofMinutes(3));

		digests.sweep(50);

		// Structural on purpose: what this test protects is that the recipient's
		// stored locale routes the whole mail, not the exact wording of any of it.
		// The wording is IssueChangeRendererTest's subject, and pinning it twice
		// only means a copy edit turns two unrelated tests red.
		assertThat(capturedModel().get("locale")).isEqualTo("de");
		assertThat(capturedSubject()).startsWith("[Hinata] HIN-42:").contains("2");
		assertThat(capturedLines()).hasSize(2);
	}

	@Test
	void aSingleChangeReadsInTheSingular() {
		queueStateChange("Open", "Done");
		advance(Duration.ofMinutes(3));

		digests.sweep(50);

		assertThat(capturedSubject()).startsWith("[Hinata] HIN-42:")
				.as("singular, not \"1 changes\"").contains("1 change");
	}
}
