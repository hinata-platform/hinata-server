package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.me.MeService;
import com.ahmadre.hinata.notification.Notification;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shared entries (HIN-95) against a real database: who may offer an entry to whom, what the
 * recipient sees before answering, the copy an acceptance files and that a second one does not,
 * the recipient's locks refusing it, the silent refusal, taking an offer back, and what an
 * account deletion leaves.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Import(TimeEntryShareIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeEntryShareIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
	static final LocalDate DAY = LocalDate.parse("2026-09-04");

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
	private TimeTrackingService timeTracking;
	@Autowired
	private TimeEntryShareService shares;

	private User sender;
	private User colleague;
	private User outsider;
	private Project project;

	@BeforeEach
	void seed() {
		for (String collection : List.of("projects", "users", "work_items", "time_entry_shares", "audit_log",
				"notifications", "server_settings", "time_tags")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		policy(null);
		sender = user("sender");
		colleague = user("colleague");
		outsider = user("outsider");
		project = projects.save(Project.builder().key("HIN").name("Hinata").leadId(sender.getId())
				.leadIds(new ArrayList<>(List.of(sender.getId())))
				.memberIds(new ArrayList<>(List.of(sender.getId(), colleague.getId()))).build());
	}

	private User user(String name) {
		return users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.roles(Set.of(Role.MEMBER)).active(true).timezone("UTC").locale("en").build());
	}

	private void policy(LocalDate lockBefore) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setLockBefore(lockBefore);
		current.setTimeTracking(block);
		settings.save(current);
	}

	private WorkItem entry(User owner, String projectId) {
		return timeTracking.create(new TimeTrackingService.NewEntry(projectId, null, null, DAY, null,
				"Pairing on the release", Instant.parse("2026-09-04T09:00:00Z"),
				Instant.parse("2026-09-04T10:30:00Z"), List.of("review"), true), WorkItem.Source.APP, owner);
	}

	private TimeEntryShare shareOf(WorkItem entry, User to) {
		return mongo.findOne(Query.query(Criteria.where("entryId").is(entry.getId()).and("toUserId").is(to.getId())),
				TimeEntryShare.class);
	}

	private static HttpStatus statusOf(Throwable thrown) {
		return ((ApiException) thrown).getStatus();
	}

	private List<Notification> notificationsOf(User user, Notification.Type type) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()).and("type").is(type)),
				Notification.class);
	}

	// --- offering -------------------------------------------------------------------------------

	@Test
	void sharesOnlyWithMembersOfTheEntrysProject() {
		WorkItem entry = entry(sender, project.getId());

		assertThatThrownBy(() -> shares.share(entry.getId(), List.of(outsider.getId()), sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> shares.share(entry.getId(), List.of("6650f0f0f0f0f0f0f0f0f0f0"), sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> shares.share(entry.getId(), List.of(sender.getId()), sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.BAD_REQUEST));
		assertThat(mongo.count(new Query(), TimeEntryShare.class)).isZero();

		List<TimeEntryShareService.ShareView> sent = shares.share(entry.getId(), List.of(colleague.getId()), sender);

		assertThat(sent).singleElement().satisfies(view -> {
			assertThat(view.status()).isEqualTo(TimeEntryShare.Status.PENDING);
			assertThat(view.to().name()).isEqualTo("colleague");
			assertThat(view.entryId()).isEqualTo(entry.getId());
		});
		assertThat(notificationsOf(colleague, Notification.Type.TIME_ENTRY_SHARED)).singleElement()
				.satisfies(notice -> assertThat(notice.getBody()).contains("sender")
						.doesNotContain("Pairing").doesNotContain("Hinata"));
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.TIME_ENTRY_SHARED)),
				AuditLog.class)).isEqualTo(1);
	}

	@Test
	void theCandidatesAreTheProjectsPeopleWithoutTheCaller() {
		Page<TimeEntryShareService.Candidate> all = shares.candidates(project.getId(), "", 0, 20, sender);
		Page<TimeEntryShareService.Candidate> searched = shares.candidates(project.getId(), "coll", 0, 20, sender);

		assertThat(all.getContent()).extracting(TimeEntryShareService.Candidate::displayName)
				.containsExactly("colleague");
		assertThat(searched.getTotalElements()).isEqualTo(1);
		assertThat(shares.candidates(project.getId(), ".*", 0, 20, sender).getTotalElements()).isZero();
		assertThatThrownBy(() -> shares.candidates(project.getId(), "", 0, 20, outsider))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void anEntryWithoutAProjectCannotBeShared() {
		WorkItem unfiled = entry(sender, null);

		assertThatThrownBy(() -> shares.share(unfiled.getId(), List.of(colleague.getId()), sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void onlyTheOwnerOffersAnEntry() {
		WorkItem entry = entry(sender, project.getId());

		assertThatThrownBy(() -> shares.share(entry.getId(), List.of(sender.getId()), colleague))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void sharingTwiceAsksOnce() {
		WorkItem entry = entry(sender, project.getId());

		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		shares.share(entry.getId(), List.of(colleague.getId()), sender);

		assertThat(mongo.count(new Query(), TimeEntryShare.class)).isEqualTo(1);
		assertThat(notificationsOf(colleague, Notification.Type.TIME_ENTRY_SHARED)).hasSize(1);
	}

	@Test
	void noMoreThanTwentyPeoplePerEntry() {
		List<String> crowd = IntStream.range(0, TimeEntryShareService.RECIPIENTS_MAX + 1)
				.mapToObj(i -> user("member" + i).getId()).toList();
		project.getMemberIds().addAll(crowd);
		projects.save(project);
		WorkItem entry = entry(sender, project.getId());

		assertThatThrownBy(() -> shares.share(entry.getId(), crowd, sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.BAD_REQUEST));
		shares.share(entry.getId(), crowd.subList(0, TimeEntryShareService.RECIPIENTS_MAX), sender);
		assertThatThrownBy(() -> shares.share(entry.getId(), crowd.subList(20, 21), sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	// --- what each side sees ----------------------------------------------------------------------

	@Test
	void theRecipientSeesTheInvitationAndNeverTheEntry() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);

		Page<TimeEntryShareService.ShareView> inbox = shares.page(TimeEntryShareService.Box.INBOX, 0, 20, colleague);

		assertThat(inbox.getTotalElements()).isEqualTo(1);
		TimeEntryShareService.ShareView invitation = inbox.getContent().getFirst();
		assertThat(invitation.entryId()).isNull();
		assertThat(invitation.from().name()).isEqualTo("sender");
		assertThat(invitation.projectName()).isEqualTo("Hinata");
		assertThat(invitation.description()).isEqualTo("Pairing on the release");
		assertThat(invitation.durationMinutes()).isEqualTo(90);
		// The entry itself stays the sender's: nothing of theirs is in the colleague's own lists.
		assertThat(timeTracking.entries(new TimeTrackingService.EntryFilter(null, null, null, null), 0, 20,
				colleague).getContent()).isEmpty();
		assertThat(timeTracking.calendar(DAY, DAY, colleague).entries()).isEmpty();
		assertThatThrownBy(() -> timeTracking.requireOwn(entry.getId(), colleague))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.NOT_FOUND));
		// And the other way round: the sender's inbox is empty, their sent list has it.
		assertThat(shares.page(TimeEntryShareService.Box.INBOX, 0, 20, sender).getTotalElements()).isZero();
		assertThat(shares.page(TimeEntryShareService.Box.SENT, 0, 20, sender).getContent()).singleElement()
				.satisfies(view -> assertThat(view.copyId()).isNull());
		assertThat(shares.page(TimeEntryShareService.Box.SENT, 0, 20, colleague).getTotalElements()).isZero();
	}

	// --- answering --------------------------------------------------------------------------------

	@Test
	void acceptingFilesExactlyOneCopy() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		String shareId = shareOf(entry, colleague).getId();

		WorkItem copy = shares.accept(shareId, null, colleague);
		WorkItem again = shares.accept(shareId, null, colleague);

		assertThat(again.getId()).isEqualTo(copy.getId());
		assertThat(copy.getUserId()).isEqualTo(colleague.getId());
		assertThat(copy.getSource()).isEqualTo(WorkItem.Source.SHARED);
		assertThat(copy.getSharedFromId()).isEqualTo(entry.getId());
		assertThat(copy.getDurationMinutes()).isEqualTo(90);
		assertThat(copy.getDescription()).isEqualTo("Pairing on the release");
		assertThat(copy.getProjectId()).isEqualTo(project.getId());
		assertThat(mongo.count(Query.query(Criteria.where("sharedFromId").is(entry.getId())), WorkItem.class))
				.isEqualTo(1);
		assertThat(shareOf(entry, colleague).getStatus()).isEqualTo(TimeEntryShare.Status.ACCEPTED);
		assertThat(notificationsOf(sender, Notification.Type.TIME_SHARE_ACCEPTED)).hasSize(1);
		// The copy is the colleague's own from now on; the original is untouched.
		assertThat(mongo.findById(entry.getId(), WorkItem.class).getUserId()).isEqualTo(sender.getId());
	}

	@Test
	void onlyTheRecipientAnswers() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		String shareId = shareOf(entry, colleague).getId();

		assertThatThrownBy(() -> shares.accept(shareId, null, outsider))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> shares.accept(shareId, null, sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> shares.decline(shareId, outsider))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void aLockedDayRefusesTheCopyAndTheInvitationWaits() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		String shareId = shareOf(entry, colleague).getId();
		policy(DAY.plusDays(1));

		// The refusal every write to a frozen day gets, with its reason for the client to read.
		assertThatThrownBy(() -> shares.accept(shareId, null, colleague))
				.satisfies(thrown -> {
					assertThat(statusOf(thrown)).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(((ApiException) thrown).getDetails()).containsEntry("reason", "lockDate");
				});
		assertThat(shareOf(entry, colleague).getStatus()).isEqualTo(TimeEntryShare.Status.PENDING);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(colleague.getId())), WorkItem.class)).isZero();

		policy(null);
		assertThat(shares.accept(shareId, null, colleague).getSharedFromId()).isEqualTo(entry.getId());
	}

	@Test
	void theRecipientMayFileTheCopyElsewhere() {
		Project own = projects.save(Project.builder().key("OWN").name("Own").leadId(colleague.getId())
				.leadIds(new ArrayList<>(List.of(colleague.getId())))
				.memberIds(new ArrayList<>(List.of(colleague.getId()))).build());
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);

		WorkItem copy = shares.accept(shareOf(entry, colleague).getId(),
				new TimeEntryShareService.Acceptance(own.getId(), null, List.of("pairing")), colleague);

		assertThat(copy.getProjectId()).isEqualTo(own.getId());
		assertThat(copy.getTags()).containsExactly("pairing");
	}

	@Test
	void decliningIsSilentAndFinal() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		String shareId = shareOf(entry, colleague).getId();

		shares.decline(shareId, colleague);

		assertThat(shareOf(entry, colleague).getStatus()).isEqualTo(TimeEntryShare.Status.DECLINED);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(sender.getId())), Notification.class)).isZero();
		assertThatThrownBy(() -> shares.accept(shareId, null, colleague))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));
		assertThatThrownBy(() -> shares.revoke(entry.getId(), colleague.getId(), sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));
		// Asking again does not reopen a refusal.
		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		assertThat(shareOf(entry, colleague).getStatus()).isEqualTo(TimeEntryShare.Status.DECLINED);
	}

	// --- taking back ------------------------------------------------------------------------------

	@Test
	void revokingWorksOnlyWhileUnanswered() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		String shareId = shareOf(entry, colleague).getId();

		assertThatThrownBy(() -> shares.revoke(entry.getId(), colleague.getId(), colleague))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.NOT_FOUND));
		shares.revoke(entry.getId(), colleague.getId(), sender);

		assertThat(shares.page(TimeEntryShareService.Box.INBOX, 0, 20, colleague).getTotalElements()).isZero();
		assertThatThrownBy(() -> shares.accept(shareId, null, colleague))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));

		// Asked again after taking it back, it is an open invitation once more.
		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		shares.accept(shareId, null, colleague);
		assertThatThrownBy(() -> shares.revoke(entry.getId(), colleague.getId(), sender))
				.satisfies(thrown -> assertThat(statusOf(thrown)).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void deletingTheEntryWithdrawsOpenInvitations() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);

		timeTracking.delete(entry.getId(), sender);

		assertThat(shareOf(entry, colleague).getStatus()).isEqualTo(TimeEntryShare.Status.REVOKED);
		assertThat(shares.page(TimeEntryShareService.Box.INBOX, 0, 20, colleague).getTotalElements()).isZero();
	}

	// --- the person's own data --------------------------------------------------------------------

	@Test
	void aDeletedAccountTakesItsInvitationsAndLeavesTheCopies() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);
		WorkItem copy = shares.accept(shareOf(entry, colleague).getId(), null, colleague);

		userService.delete(users.findById(sender.getId()).orElseThrow());

		assertThat(mongo.count(new Query(), TimeEntryShare.class)).isZero();
		assertThat(mongo.findById(copy.getId(), WorkItem.class)).isNotNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void theExportCarriesBothSides() {
		WorkItem entry = entry(sender, project.getId());
		shares.share(entry.getId(), List.of(colleague.getId()), sender);

		Map<String, Object> sent = (Map<String, Object>) me.exportData(sender).get("sharedTimeEntries");
		Map<String, Object> received = (Map<String, Object>) me.exportData(colleague).get("sharedTimeEntries");

		assertThat((List<Object>) sent.get("sent")).hasSize(1);
		assertThat((List<Object>) received.get("received")).hasSize(1);
		assertThat((List<Object>) received.get("sent")).isEmpty();
	}
}
