package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policies of stage 6, against a real database: what an entry must carry,
 * which days are frozen, who may coin a tag, and what is written down about the
 * operator's decisions.
 *
 * <p>Testcontainers rather than mocks, because most of these claims are about
 * MongoDB doing something — a unique index settling two tags with the same
 * name, a rename rewriting two thousand entries in batches, an entry loading
 * back with the tag the catalogue spells. A mocked repository would confirm the
 * code's own opinion of itself.
 *
 * <p>The clock is frozen: "before the lock date" is a statement about a
 * boundary, and a test that asserts it against the wall clock either drifts or
 * never touches the edge.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimePolicyIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimePolicyIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
	private static final LocalDate YESTERDAY = TODAY.minusDays(1);

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
	private TimeTrackingService timeTracking;
	@Autowired
	private TimeTagService tagCatalog;
	@Autowired
	private TimerService timers;
	@Autowired
	private SettingsService settings;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private TimeTagRepository tags;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;

	private User member;
	private User admin;
	private Project project;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"running_timers", "time_tags", "project_time_settings", "audit_log",
				"server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		// Every test starts with the module on and no administrator opinion about
		// anything else: the policies of this stage only act while the module
		// does, so a suite that left it off would be asserting nothing.
		ServerSettings fresh = new ServerSettings();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		fresh.setTimeTracking(block);
		settings.save(fresh);
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		member = user("member", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
		issue = issueRepository.save(Issue.builder().projectId(project.getId()).readableId("HIN-1")
				.numberInProject(1).title("Login bug").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private User user(String name, Role role) {
		User saved = users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").build());
		project.getMemberIds().add(saved.getId());
		project = projects.save(project);
		return saved;
	}

	// --- policy plumbing ---------------------------------------------------------

	private ServerSettings.TimeTracking policy() {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = current.getTimeTracking();
		if (block == null) {
			block = new ServerSettings.TimeTracking();
			block.setAdvancedEnabled(true);
			current.setTimeTracking(block);
		}
		return block;
	}

	/** Stores a policy change the way the admin PUT does — the event refreshes the cache. */
	private void store(ServerSettings.TimeTracking block) {
		ServerSettings current = settings.get();
		current.setTimeTracking(block);
		settings.save(current);
	}

	private void require(boolean project, boolean issue, boolean description, boolean tag) {
		ServerSettings.TimeTracking block = policy();
		ServerSettings.TimeTracking.RequiredFields required =
				new ServerSettings.TimeTracking.RequiredFields();
		required.setProject(project);
		required.setIssue(issue);
		required.setDescription(description);
		required.setTag(tag);
		block.setRequiredFields(required);
		store(block);
	}

	private void lockBefore(LocalDate day) {
		ServerSettings.TimeTracking block = policy();
		block.setLockBefore(day);
		store(block);
	}

	private void limitTagAccess(boolean limited) {
		ServerSettings.TimeTracking block = policy();
		block.setLimitTagAccess(limited);
		store(block);
	}

	// --- fixtures -----------------------------------------------------------------

	private TimeTrackingService.NewEntry entry(String projectId, String issueId,
			String description, List<String> tagNames) {
		return new TimeTrackingService.NewEntry(projectId, issueId, 30, TODAY, null, description,
				null, null, tagNames, null);
	}

	private WorkItem file(TimeTrackingService.NewEntry draft, User as) {
		return timeTracking.create(draft, WorkItem.Source.APP, as);
	}

	private void assertRefused(TimeTrackingService.NewEntry draft, User as, HttpStatus status,
			String key) {
		assertThatThrownBy(() -> file(draft, as))
				.isInstanceOf(ApiException.class)
				.hasMessage(key)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(status);
	}

	// --- required fields -------------------------------------------------------------

	@Test
	void aRequiredProjectRefusesAnUnfiledEntryAndAcceptsAFiledOne() {
		require(true, false, false, false);

		assertRefused(entry(null, null, "worked", List.of()), member, HttpStatus.BAD_REQUEST,
				"error.time.required.project");
		assertThat(file(entry(project.getId(), null, "worked", List.of()), member).getProjectId())
				.isEqualTo(project.getId());
	}

	@Test
	void aRequiredIssueImpliesAProject() {
		require(false, true, false, false);

		assertRefused(entry(project.getId(), null, "worked", List.of()), member,
				HttpStatus.BAD_REQUEST, "error.time.required.issue");
		assertThat(file(entry(null, issue.getId(), "worked", List.of()), member).getProjectId())
				.isEqualTo(project.getId());
	}

	@Test
	void aRequiredDescriptionRefusesBlankText() {
		require(false, false, true, false);

		assertRefused(entry(null, null, "   ", List.of()), member, HttpStatus.BAD_REQUEST,
				"error.time.required.description");
		assertThat(file(entry(null, null, "pairing", List.of()), member).getDescription())
				.isEqualTo("pairing");
	}

	@Test
	void aRequiredTagRefusesAnEntryWithNone() {
		require(false, false, false, true);

		assertRefused(entry(null, null, "worked", List.of()), member, HttpStatus.BAD_REQUEST,
				"error.time.required.tag");
		assertThat(file(entry(null, null, "worked", List.of("meeting")), member).getTags())
				.containsExactly("meeting");
	}

	@Test
	void anEntryThatPredatesARequiredFieldCanStillBeBroughtIntoCompliance() {
		// The rollout order every instance takes: entries first, policy after. If
		// the ownership pre-check judged the required fields, the one patch that
		// adds the missing tag would be refused along with every other edit, and
		// forty thousand entries would be read-only for their owners, their leads
		// and the administrator alike.
		WorkItem old = file(entry(null, null, "worked", List.of()), member);
		require(false, false, false, true);

		WorkItem fixed = timeTracking.update(old.getId(),
				new TimeTrackingService.WorkItemPatch(null, null, null, null, false, null, false,
						null, List.of("meeting"), null), member);

		assertThat(fixed.getTags()).containsExactly("meeting");
		// And an edit that leaves it non-compliant is still refused — the policy
		// applies to the result, which is the whole point of it.
		assertThatThrownBy(() -> timeTracking.update(fixed.getId(),
				new TimeTrackingService.WorkItemPatch(45, null, null, null, false, null, false,
						null, List.of(), null), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.required.tag");
	}

	@Test
	void aTagSuppliedWhenTheTimerStopsGoesThroughTheCatalogueToo() {
		// The stop body carries its own tags. Without resolving them, a member on
		// a curated instance could write any word onto their entry, and on an open
		// one "meeting" typed here would sit beside the catalogue's "Meeting" and
		// split every report by tag.
		tagCatalog.create("Meeting", null, admin);
		timers.start(TimerService.StartDraft.stopwatch(
				new TimerService.TimerDraft(null, null, "worked", null, List.of(), false)), member);

		TimerService.Stopped stopped = timers.stop(new TimerService.StopRequest(null,
				NOW.plusSeconds(3600), null, null, null, null, List.of("meeting"), null), member);

		assertThat(stopped.entry().getTags()).containsExactly("Meeting");
	}

	@Test
	void aRefusedWriteLeavesNothingInTheCatalogue() {
		// The catalogue is touched last, after every reason to refuse. Otherwise a
		// request that answers 403 still coins twenty words — and, before the
		// audit was narrowed, twenty configuration records with it — which anyone
		// signed in could repeat.
		lockBefore(TODAY);

		assertThatThrownBy(() -> file(new TimeTrackingService.NewEntry(null, null, 30, YESTERDAY,
				null, "worked", null, null, List.of("brand new word"), null), member))
				.isInstanceOf(ApiException.class);

		assertThat(tags.count()).isZero();
	}

	@Test
	void onlyAnAdministratorRenamesOrDeletesATag() {
		// Coining a word is cheap and undoable, and the operator decides who may.
		// Renaming one rewrites a label on every entry that carries it, in
		// projects the actor may not even be able to see — that is an
		// administrator's act whatever limitTagAccess says. It was the default-off
		// state that made this reachable: with the flag off, the coinage check was
		// a no-op for everybody.
		limitTagAccess(false);
		TimeTag tag = tagCatalog.create("meeting", null, member);

		assertThatThrownBy(() -> tagCatalog.update(tag.getId(), "gone", null, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.tagsRestricted");
		assertThatThrownBy(() -> tagCatalog.delete(tag.getId(), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.tagsRestricted");
		assertThat(tags.findById(tag.getId())).isPresent();
	}

	@Test
	void aRenameLeavesFrozenDaysAlone() {
		// The freeze binds every write path in the module, administrators
		// included. A rename is a write to every entry it touches, so a closed
		// payroll period must come out of it unchanged.
		TimeTag tag = tagCatalog.create("meeting", null, admin);
		WorkItem frozen = file(new TimeTrackingService.NewEntry(null, null, 30, YESTERDAY, null,
				"worked", null, null, List.of("meeting"), null), member);
		WorkItem open = file(entry(null, null, "worked", List.of("meeting")), member);
		lockBefore(TODAY);

		assertThat(tagCatalog.update(tag.getId(), "Team meeting", null, admin).entries())
				.isEqualTo(1);

		assertThat(workItems.findById(frozen.getId()).orElseThrow().getTags())
				.containsExactly("meeting");
		assertThat(workItems.findById(open.getId()).orElseThrow().getTags())
				.containsExactly("Team meeting");
	}

	@Test
	void withTheModuleOffTheOneDRoutesBehaveExactlyAsBefore() {
		// The 1.x routes the published app talks to reach the same service and are
		// not behind the gate. Switching the module off has to leave nothing of it
		// running: no required field, no freeze, and nothing written into a
		// collection it owns.
		require(true, true, true, true);
		lockBefore(TODAY.plusDays(1));
		ServerSettings.TimeTracking block = policy();
		block.setAdvancedEnabled(false);
		store(block);

		WorkItem filed = timeTracking.add(issue.getId(),
				new TimeTrackingService.NewWorkItem(30, YESTERDAY, null, null, null, null,
						List.of("ad hoc"), null),
				WorkItem.Source.SMART_COMMIT, member);

		assertThat(filed.getTags()).containsExactly("ad hoc");
		assertThat(tags.count()).isZero();
	}

	@Test
	void requiredFieldsHoldOnTheSmartCommitPathToo() {
		// The 1.x route the git handler and the MCP tool both file through. A rule
		// a push could walk around would not be a rule.
		require(false, false, false, true);

		assertThatThrownBy(() -> timeTracking.add(issue.getId(),
				new TimeTrackingService.NewWorkItem(30, TODAY, null, "Smart commit abc1234: fix",
						null, null, null, null),
				WorkItem.Source.SMART_COMMIT, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.required.tag");
	}

	@Test
	void aTimerStartsWithNothingEvenWhereEverythingIsRequired() {
		// This used to refuse, and it made the stopwatch unstartable: there is no
		// field on the start path to type a description into, so an instance that
		// required one had a button that could only ever say no. A running timer
		// is not an entry — the entry is born at the stop, and that is where the
		// client asks for what is missing.
		require(true, true, true, true);

		RunningTimer running = timers.start(TimerService.StartDraft.stopwatch(
				new TimerService.TimerDraft(null, null, null, null, List.of(), false)), member);

		assertThat(running.getProjectId()).isNull();
		assertThat(running.getDescription()).isNull();
		assertThat(mongo.getCollection("running_timers").countDocuments()).isEqualTo(1);
	}

	@Test
	void aRunningTimerCanBeFiledMidRunWithoutSatisfyingTheRestOfThePolicy() {
		// Two required fields, one of them filled from the bar's placement row.
		// Checked here, the patch would refuse because the *other* one is still
		// empty — leaving no way to fill in the first, which is the only field
		// this screen has.
		require(true, false, true, false);
		timers.start(TimerService.StartDraft.stopwatch(
				new TimerService.TimerDraft(null, null, null, null, List.of(), false)), member);

		RunningTimer filed = timers.patch(
				new TimerService.TimerDraft(project.getId(), null, null, null, List.of(), false),
				member);

		assertThat(filed.getProjectId()).isEqualTo(project.getId());
		assertThat(filed.getDescription()).isNull();
	}

	@Test
	void andTheStopCarriesWhatTheComposerCollected() {
		// The other half of moving the rule: the app opens the composer when the
		// policy asks for more than the timer holds, and its answers ride along on
		// the stop request. The stop itself never refuses — see TimerService#stop
		// for why a pomodoro turning over is what settles that.
		tagCatalog.create("Meeting", null, admin);
		require(true, false, true, true);
		timers.start(TimerService.StartDraft.stopwatch(
				new TimerService.TimerDraft(null, null, null, null, List.of(), false)), member);

		TimerService.Stopped stopped = timers.stop(new TimerService.StopRequest(null,
				NOW.plusSeconds(3600), project.getId(), null, "worked", null, List.of("meeting"),
				null), member);

		assertThat(stopped.entry().getProjectId()).isEqualTo(project.getId());
		assertThat(stopped.entry().getDescription()).isEqualTo("worked");
		assertThat(stopped.entry().getTags()).containsExactly("Meeting");
	}

	@Test
	void aRunningTimerStillFilesAfterThePolicyTightens() {
		// Started when nothing was required, stopped after an administrator turned
		// a field on. The interval was worked; refusing it here would lose it and
		// strand the timer, so the policy applies to what people type, not to what
		// the clock already measured.
		timers.start(TimerService.StartDraft.stopwatch(
				new TimerService.TimerDraft(null, null, "worked", null, List.of(), false)), member);
		require(true, true, true, true);

		TimerService.Stopped stopped = timers.stop(new TimerService.StopRequest(null,
				NOW.plusSeconds(3600), null, null, null, null, null, null), member);

		assertThat(stopped.entry().getDurationMinutes()).isEqualTo(60);
		assertThat(stopped.entry().getProjectId()).isNull();
	}

	// --- the lock date ----------------------------------------------------------------

	@Test
	void aFrozenDayRefusesEveryWriteFromEveryRole() {
		WorkItem existing = file(new TimeTrackingService.NewEntry(null, null, 30, YESTERDAY, null,
				"worked", null, null, List.of(), null), member);
		lockBefore(TODAY);

		assertRefused(new TimeTrackingService.NewEntry(null, null, 30, YESTERDAY, null, "more",
				null, null, List.of(), null), member, HttpStatus.FORBIDDEN, "error.time.locked");
		for (User actor : List.of(member, admin)) {
			// The administrator too. A freeze that does not bind the most powerful
			// account is not a freeze; lifting the date is the audited way through.
			assertThatThrownBy(() -> timeTracking.update(existing.getId(),
					new TimeTrackingService.WorkItemPatch(45, null, null, null, false, null,
							false, null, null, null), actor))
					.isInstanceOf(ApiException.class)
					.hasMessage("error.time.locked");
			assertThatThrownBy(() -> timeTracking.delete(existing.getId(), actor))
					.isInstanceOf(ApiException.class)
					.hasMessage("error.time.locked");
		}
		assertThat(workItems.findById(existing.getId())).isPresent();
	}

	@Test
	void anEntryCannotBeMovedOffAFrozenDay() {
		// Both sides of the change, which is why the gate is handed two entries: a
		// lock that only guarded the destination would be one anybody could walk
		// out of.
		WorkItem old = file(new TimeTrackingService.NewEntry(null, null, 30, YESTERDAY, null,
				"worked", null, null, List.of(), null), member);
		lockBefore(TODAY);

		assertThatThrownBy(() -> timeTracking.update(old.getId(),
				new TimeTrackingService.WorkItemPatch(null, TODAY, null, null, false, null, false,
						null, null, null), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.locked");
	}

	@Test
	void anUnfiledEntryIsCoveredByTheLockToo() {
		// Otherwise the lock has a door in it: file the hours under no project and
		// the frozen week is editable again.
		lockBefore(TODAY);

		assertRefused(new TimeTrackingService.NewEntry(null, null, 30, YESTERDAY, null, "worked",
				null, null, List.of(), null), member, HttpStatus.FORBIDDEN, "error.time.locked");
	}

	@Test
	void aTimerWhoseDayIsFrozenIsDiscardedRatherThanLeftRunning() {
		timers.start(TimerService.StartDraft.stopwatch(
				new TimerService.TimerDraft(null, null, "worked", null, List.of(), false)), member);
		// A lock that reaches tomorrow freezes the day this timer started on —
		// the only way the case is reachable, since a timer cannot outlive its
		// start by more than a day.
		lockBefore(TODAY.plusDays(1));

		assertThatThrownBy(() -> timers.stop(new TimerService.StopRequest(null,
				NOW.plusSeconds(600), null, null, null, null, null, null), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.lockedTimer");
		// Gone, not stuck: every further stop would answer 403 while the clock
		// kept running.
		assertThat(mongo.getCollection("running_timers").countDocuments()).isZero();
		assertThat(workItems.count()).isZero();
	}

	// --- tags ----------------------------------------------------------------------------

	@Test
	void aTagIsCoinedOnceHoweverItIsSpelled() {
		file(entry(null, null, "worked", List.of("Meeting")), member);
		WorkItem second = file(entry(null, null, "worked", List.of("meeting", "MEETING")), member);

		assertThat(tags.count()).isEqualTo(1);
		// Both spellings collapse onto the catalogue's, and the entry does not end
		// up carrying the same word twice.
		assertThat(second.getTags()).containsExactly("Meeting");
	}

	@Test
	void aCuratedCatalogueRefusesAnUnknownTagAndOnlyAdminsAddOne() {
		tagCatalog.create("Meeting", null, admin);
		limitTagAccess(true);

		assertRefused(entry(null, null, "worked", List.of("review")), member, HttpStatus.FORBIDDEN,
				"error.time.tagNotAllowed");
		assertThatThrownBy(() -> tagCatalog.create("review", null, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.tagsRestricted");
		assertThat(tagCatalog.create("Review", null, admin).getName()).isEqualTo("Review");
		assertThat(file(entry(null, null, "worked", List.of("meeting")), member).getTags())
				.containsExactly("Meeting");
	}

	@Test
	void anOpenCatalogueLetsAnyMemberCoinOne() {
		limitTagAccess(false);

		assertThat(tagCatalog.create("review", null, member).getName()).isEqualTo("review");
		assertThat(file(entry(null, null, "worked", List.of("pairing")), member).getTags())
				.containsExactly("pairing");
		assertThat(tags.findByNormalized("pairing")).isPresent();
	}

	@Test
	void renamingATagCarriesEveryEntryWithIt() {
		// More than two batches of five hundred, so the cascade is exercised as
		// the loop it is rather than as a single update.
		TimeTag tag = tagCatalog.create("meeting", null, admin);
		List<WorkItem> batch = new ArrayList<>();
		for (int i = 0; i < 1_200; i++) {
			batch.add(WorkItem.builder().userId(member.getId()).date(TODAY).durationMinutes(15)
					.activityType("Development").tags(new ArrayList<>(List.of("meeting")))
					.source(WorkItem.Source.APP).build());
		}
		workItems.saveAll(batch);

		TimeTagService.TagUsage renamed = tagCatalog.update(tag.getId(), "Team meeting", null, admin);

		assertThat(renamed.entries()).isEqualTo(1_200);
		assertThat(mongo.count(Query.query(Criteria.where("tags").is("Team meeting")),
				WorkItem.class)).isEqualTo(1_200);
		assertThat(mongo.count(Query.query(Criteria.where("tags").is("meeting")),
				WorkItem.class)).isZero();
	}

	@Test
	void aRenameThatOnlyChangesCapitalisationDoesNotEmptyTheField() {
		// The pull spares the name that was just added, or the two halves of the
		// rewrite would cancel each other out.
		TimeTag tag = tagCatalog.create("meeting", null, admin);
		WorkItem item = file(entry(null, null, "worked", List.of("meeting")), member);

		tagCatalog.update(tag.getId(), "Meeting", null, admin);

		assertThat(workItems.findById(item.getId()).orElseThrow().getTags())
				.containsExactly("Meeting");
	}

	@Test
	void deletingATagTakesItOffTheEntriesAndLeavesThemStanding() {
		TimeTag tag = tagCatalog.create("meeting", null, admin);
		WorkItem item = file(entry(null, null, "worked", List.of("meeting", "review")), member);

		assertThat(tagCatalog.delete(tag.getId(), admin)).isEqualTo(1);

		WorkItem after = workItems.findById(item.getId()).orElseThrow();
		assertThat(after.getTags()).containsExactly("review");
		assertThat(after.getDurationMinutes()).isEqualTo(30);
	}

	@Test
	void twoTagsWithTheSameNameCannotBothExist() {
		tagCatalog.create("meeting", null, admin);

		assertThatThrownBy(() -> tagCatalog.create("Meeting", null, admin))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.tagExists");
	}

	// --- what is written down --------------------------------------------------------------

	@Test
	void movingTheLockDateIsRecordedOnItsOwn() {
		// Its own event, not a SETTINGS_CHANGED among forty: a works council
		// asking who opened January has to find one record with one name on it.
		ServerSettings before = settings.get();
		ServerSettings after = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setLockBefore(TODAY);
		after.setTimeTracking(block);

		new TimeTrackingSettingsAudit(auditService()).record(before, after, admin);

		AuditLog record = onlyRecordOf(AuditAction.TIME_LOCK_CHANGED);
		assertThat(record.getActorId()).isEqualTo(admin.getId());
		assertThat(record.getMetadata()).containsEntry("lockBefore", "default")
				.containsEntry("lockAfter", TODAY.toString());
	}

	@Test
	void curatingATagIsRecordedWithBothNames() {
		TimeTag tag = tagCatalog.create("meeting", null, admin);

		tagCatalog.update(tag.getId(), "Team meeting", null, admin);

		AuditLog record = onlyRecordOf(AuditAction.TIME_TAG_UPDATED);
		assertThat(record.getMetadata()).containsEntry("nameBefore", "meeting")
				.containsEntry("nameAfter", "Team meeting");
	}

	@Test
	void ordinaryUseOfTheModuleIsNotRecordedUnlessAnOperatorAsksForIt() {
		// A complete log of when each person started and stopped working is
		// exactly what § 87 Abs. 1 Nr. 6 BetrVG is about, so it is never a default
		// an instance inherits. The names exist; the recording does not.
		timers.start(TimerService.StartDraft.stopwatch(
				new TimerService.TimerDraft(null, null, "worked", null, List.of(), false)), member);
		timers.discard(member);
		file(entry(null, null, "worked", List.of()), member);

		assertThat(AuditAction.TIME_TIMER_STARTED.defaultEnabled()).isFalse();
		assertThat(AuditAction.TIME_TIMER_STOPPED.defaultEnabled()).isFalse();
		assertThat(AuditAction.TIME_TIMER_DISCARDED.defaultEnabled()).isFalse();
		assertThat(AuditAction.TIME_ENTRY_CREATED.defaultEnabled()).isFalse();
		assertThat(mongo.count(Query.query(Criteria.where("action")
				.in(AuditAction.TIME_TIMER_STARTED.name(), AuditAction.TIME_TIMER_DISCARDED.name(),
						AuditAction.TIME_ENTRY_CREATED.name())), AuditLog.class)).isZero();
	}

	@Test
	void aTimerRecordNeverSaysHowTheTimerWasOperated() {
		// Stage 18 puts the timer in the notification shade, the menu bar and a
		// watch face. None of those may become a field here: a record naming the
		// device is a location trail, and the surfaces are meant to be
		// indistinguishable from a tap in the app.
		enable(AuditAction.TIME_TIMER_STARTED);

		timers.start(TimerService.StartDraft.stopwatch(
				new TimerService.TimerDraft(project.getId(), null, "worked", null, List.of(), false)),
				member);

		AuditLog record = onlyRecordOf(AuditAction.TIME_TIMER_STARTED);
		// `workItem` is the same id under the key the entry's own history reads;
		// nothing else. No surface, no device, no client.
		assertThat(record.getMetadata())
				.containsOnlyKeys("timer", "workItem", "mode", "project");
	}

	// --- the history of one entry -------------------------------------------------------------

	@Test
	void theOwnerSeesWhoChangedTheirEntryAndAStrangerDoesNot() {
		project.getLeadIds().add(admin.getId());
		projects.save(project);
		WorkItem item = file(entry(project.getId(), null, "worked", List.of()), member);
		// An administrator editing somebody else's record — the case the trail
		// exists for.
		timeTracking.update(item.getId(), new TimeTrackingService.WorkItemPatch(45, null, null,
				null, false, null, false, null, null, null), admin);

		Page<AuditLog> history = timeTracking.history(item.getId(), 0, 50, member);

		assertThat(history.getTotalElements()).isEqualTo(1);
		assertThat(history.getContent().getFirst().getAction())
				.isEqualTo(AuditAction.TIME_ENTRY_UPDATED);
		assertThat(history.getContent().getFirst().getActorId()).isEqualTo(admin.getId());

		// Not found, not forbidden: a route that told a stranger the difference
		// would let them learn which entries exist.
		User stranger = user("stranger", Role.MEMBER);
		assertThatThrownBy(() -> timeTracking.history(item.getId(), 0, 50, stranger))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.notFound");
	}

	// --- helpers ------------------------------------------------------------------------------

	private com.ahmadre.hinata.audit.AuditService auditService() {
		return auditBean;
	}

	@Autowired
	private com.ahmadre.hinata.audit.AuditService auditBean;

	private void enable(AuditAction action) {
		ServerSettings current = settings.get();
		ServerSettings.Audit audit = current.getAudit();
		if (audit == null) {
			audit = new ServerSettings.Audit();
			current.setAudit(audit);
		}
		audit.getEvents().put(action.name(), true);
		settings.save(current);
	}

	private AuditLog onlyRecordOf(AuditAction action) {
		List<AuditLog> found = mongo.find(
				Query.query(Criteria.where("action").is(action.name())), AuditLog.class);
		assertThat(found).hasSize(1);
		return found.getFirst();
	}
}
