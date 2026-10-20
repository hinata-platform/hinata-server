package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.PushService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

/**
 * The notifications of HIN-46 against a real MongoDB replica set: who hears about what, who never
 * does, and that a reminder goes out once per assignment however the runs fall.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.mongodb.transactions-enabled=true",
		"hinata.shifts.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class ShiftNotificationIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@MockitoBean
	private MailService mail;
	@MockitoBean
	private PushService push;

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private SettingsService settings;
	@Autowired
	private ShiftService service;
	@Autowired
	private ShiftAssignments assignments;
	@Autowired
	private ShiftPlanners planners;
	@Autowired
	private ShiftReminderJob job;
	@Autowired
	@Qualifier(ShiftFollowUps.EXECUTOR)
	private ThreadPoolTaskExecutor followUps;

	private User lead;
	private User pia;
	private User ann;
	private User bob;
	private Project project;
	private Instant base;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "shift_events", "shifts", "shift_assignments",
				"shift_user_guards", "shift_planners", "notifications", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		pia = user("pia");
		ann = user("ann");
		bob = user("bob");
		project = projects.save(Project.builder().key("FEST").name("FEST").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), pia.getId(), ann.getId(), bob.getId()))).build());
		base = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofDays(10));
		clearInvocations(push, mail);
	}

	// --- assignment and release ---------------------------------------------------------

	@Test
	@DisplayName("a planner's assignment tells the person; a sign-up of one's own tells nobody")
	void assignedOnlyByAPlanner() {
		Shift shift = shift(event(), base, 2, 3, null);

		assignments.assign(ann, shift.getId(), ann.getId(), true);
		assertThat(count(null, null)).isZero();

		assignments.assign(lead, shift.getId(), bob.getId(), false);
		assertThat(count(bob, Notification.Type.SHIFT_ASSIGNED)).isEqualTo(1);
		assertThat(count(lead, null)).isZero();
		Notification bell = one(bob, Notification.Type.SHIFT_ASSIGNED);
		assertThat(bell.getBody()).contains("Aufbau").contains("Sommerfest");
		assertThat(bell.getLink()).isEqualTo("/shifts/events/" + shift.getEventId());
	}

	@Test
	@DisplayName("a planner taking somebody off tells them; giving up one's own place does not")
	void releasedByAPlanner() {
		Shift shift = shift(event(), base, 2, 1, null);
		String bobs = assignments.assign(lead, shift.getId(), bob.getId(), false).assignment().getId();
		String anns = assignments.assign(ann, shift.getId(), ann.getId(), true).assignment().getId();

		assignments.release(lead, bobs);
		settle();
		assignments.release(ann, anns);
		settle();

		assertThat(count(bob, Notification.Type.SHIFT_RELEASED)).isEqualTo(1);
		assertThat(one(bob, Notification.Type.SHIFT_RELEASED).getLink()).isEqualTo("/shifts");
		assertThat(count(ann, Notification.Type.SHIFT_RELEASED)).isZero();
		assertThat(count(lead, Notification.Type.SHIFT_RELEASED)).isZero();
	}

	@Test
	@DisplayName("planners hear a shift left short at most once an hour, the leaver hears nothing")
	void shortNoticeThrottled() {
		planners.grant(lead, project.getId(), pia.getId());
		Shift shift = shift(event(), base, 2, 2, null);
		String anns = assignments.assign(ann, shift.getId(), ann.getId(), true).assignment().getId();
		String bobs = assignments.assign(bob, shift.getId(), bob.getId(), true).assignment().getId();

		assignments.release(ann, anns);
		settle();
		assignments.release(bob, bobs);
		settle();
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		String again = mongo.findOne(Query.query(Criteria.where("userId").is(ann.getId())), ShiftAssignment.class).getId();
		assignments.release(ann, again);
		settle();

		assertThat(count(lead, Notification.Type.SHIFT_HELPER_DECLINED)).isEqualTo(1);
		assertThat(count(pia, Notification.Type.SHIFT_HELPER_DECLINED)).isEqualTo(1);
		assertThat(count(ann, Notification.Type.SHIFT_HELPER_DECLINED)).isZero();
		assertThat(count(bob, Notification.Type.SHIFT_HELPER_DECLINED)).isZero();
		assertThat(one(lead, Notification.Type.SHIFT_HELPER_DECLINED).getBody()).doesNotContain("Ann", "Bob");

		// After the hour the next leaver tells them again.
		mongo.updateFirst(Query.query(Criteria.where("_id").is(shift.getId())),
				new org.springframework.data.mongodb.core.query.Update().set("lastShortNoticeAt",
						Instant.now().minus(Duration.ofMinutes(61))), Shift.class);
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		assignments.release(ann, again);
		settle();
		assertThat(count(lead, Notification.Type.SHIFT_HELPER_DECLINED)).isEqualTo(2);
	}

	@Test
	@DisplayName("a shift still at or above what it needs tells no planner")
	void noShortNoticeWhileCovered() {
		Shift shift = shift(event(), base, 2, 1, null);
		String anns = assignments.assign(ann, shift.getId(), ann.getId(), true).assignment().getId();
		assignments.assign(bob, shift.getId(), bob.getId(), true);
		assignments.release(ann, anns);
		settle();
		assertThat(count(lead, Notification.Type.SHIFT_HELPER_DECLINED)).isZero();
	}

	@Test
	@DisplayName("leaving a shift of a cancelled event tells no planner: it needs nobody any more")
	void noShortNoticeForACancelledShift() {
		ShiftEvent event = event();
		Shift shift = shift(event, base, 2, 2, null);
		String anns = assignments.assign(ann, shift.getId(), ann.getId(), true).assignment().getId();
		service.cancelEvent(lead, event.getId(), null);
		assignments.release(ann, anns);
		settle();
		assertThat(count(lead, Notification.Type.SHIFT_HELPER_DECLINED)).isZero();
	}

	// --- event and shift changes -------------------------------------------------------

	@Test
	@DisplayName("a moved or cancelled event tells each person once, not once per place, and not the actor")
	void eventChangedOncePerPerson() {
		ShiftEvent event = event();
		Shift early = shift(event, base, 2, 2, null);
		Shift late = shift(event, base.plus(Duration.ofHours(3)), 2, 2, null);
		assignments.assign(ann, early.getId(), ann.getId(), true);
		assignments.assign(ann, late.getId(), ann.getId(), true);
		assignments.assign(bob, early.getId(), bob.getId(), true);
		assignments.assign(lead, late.getId(), lead.getId(), true);

		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch(null, null, null,
				base.plus(Duration.ofDays(1)), null, null, null, null), null);
		assertThat(count(ann, Notification.Type.SHIFT_EVENT_CHANGED)).isEqualTo(1);
		assertThat(count(bob, Notification.Type.SHIFT_EVENT_CHANGED)).isEqualTo(1);
		assertThat(count(lead, Notification.Type.SHIFT_EVENT_CHANGED)).isZero();

		// A change that moves nothing tells nobody.
		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch("Sommerfest 2026", null, null, null,
				null, null, null, null), null);
		assertThat(count(ann, Notification.Type.SHIFT_EVENT_CHANGED)).isEqualTo(1);

		service.cancelEvent(lead, event.getId(), null);
		service.cancelEvent(lead, event.getId(), null);
		assertThat(count(ann, Notification.Type.SHIFT_EVENT_CHANGED)).isEqualTo(2);
		assertThat(count(bob, Notification.Type.SHIFT_EVENT_CHANGED)).isEqualTo(2);
		assertThat(count(lead, null)).isZero();
	}

	@Test
	@DisplayName("a moved or deleted shift tells its people")
	void shiftChanged() {
		ShiftEvent event = event();
		Shift shift = shift(event, base, 2, 2, null);
		assignments.assign(ann, shift.getId(), ann.getId(), true);

		service.updateShift(lead, shift.getId(), new ShiftService.ShiftPatch(null, base.plus(Duration.ofHours(1)),
				base.plus(Duration.ofHours(3)), null, null, null), null);
		service.updateShift(lead, shift.getId(), new ShiftService.ShiftPatch(null, null, null, 3, null, null), null);
		assertThat(count(ann, Notification.Type.SHIFT_CHANGED)).isEqualTo(1);

		service.deleteShift(lead, shift.getId(), null);
		assertThat(count(ann, Notification.Type.SHIFT_CHANGED)).isEqualTo(2);
		assertThat(count(lead, null)).isZero();
	}

	@Test
	@DisplayName("somebody who lost the team grant hears nothing, the members do")
	void invisibleRecipientsHearNothing() {
		User dana = user("dana");
		Team team = teams.save(Team.builder().key("OPS").name("Ops").createdBy(lead.getId())
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(TeamMembership.builder().userId(dana.getId()).role(TeamRole.MEMBER)
						.access(ProjectAccess.all()).build())))
				.build());
		ShiftEvent event = event();
		Shift shift = shift(event, base, 2, 3, null);
		assignments.assign(lead, shift.getId(), dana.getId(), false);
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		assertThat(count(dana, Notification.Type.SHIFT_ASSIGNED)).isEqualTo(1);

		// The grant goes behind the module's back, so the place stays and only the sight is gone.
		team.setProjectIds(new ArrayList<>());
		teams.save(team);
		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch(null, null, null,
				base.plus(Duration.ofHours(1)), null, null, null, null), null);
		job.run(base.plus(Duration.ofHours(1)).minus(Duration.ofHours(23)));

		assertThat(count(ann, Notification.Type.SHIFT_EVENT_CHANGED)).isEqualTo(1);
		assertThat(count(ann, Notification.Type.SHIFT_REMINDER)).isEqualTo(1);
		assertThat(count(dana, Notification.Type.SHIFT_EVENT_CHANGED)).isZero();
		assertThat(count(dana, Notification.Type.SHIFT_REMINDER)).isZero();
	}

	// --- reminders ---------------------------------------------------------------------

	@Test
	@DisplayName("a reminder goes out once, with two runs at once, after a restart and in overlapping windows")
	void reminderExactlyOnce() throws Exception {
		Shift shift = shift(event(), base, 2, 3, null);
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		assignments.assign(lead, shift.getId(), bob.getId(), false);
		Instant t = base.minus(Duration.ofHours(23));

		List<Integer> claimed = parallel(() -> job.run(t), () -> job.run(t));
		assertThat(claimed.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
		assertThat(job.run(t)).isZero(); // a restart runs the same hour again
		assertThat(job.run(t.plus(Duration.ofHours(1)))).isZero(); // the next window covers the start too
		assertThat(count(ann, Notification.Type.SHIFT_REMINDER)).isEqualTo(1);
		assertThat(count(bob, Notification.Type.SHIFT_REMINDER)).isEqualTo(1);
		assertThat(one(ann, Notification.Type.SHIFT_REMINDER).getLink()).isEqualTo("/shifts/events/" + shift.getEventId());
	}

	@Test
	@DisplayName("the window hangs on the planned hour: too early and too late remind nobody")
	void reminderWindow() {
		Shift shift = shift(event(), base, 2, 3, null);
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		assertThat(job.run(base.minus(Duration.ofHours(25)))).isZero(); // starts at t+25h
		assertThat(job.run(base.minus(Duration.ofHours(22)))).isEqualTo(1); // starts at t+22h, the lower bound
		assertThat(job.run(base.minus(Duration.ofHours(21)))).isZero();
	}

	@Test
	@DisplayName("a move more than a day ahead reminds again; a cancelled event reminds nobody")
	void reminderAfterMoveAndCancel() {
		ShiftEvent event = event();
		Shift shift = shift(event, base, 2, 3, null);
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		assertThat(job.run(base.minus(Duration.ofHours(23)))).isEqualTo(1);

		Instant later = base.plus(Duration.ofDays(2));
		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch(null, null, null, later, null, null, null, null), null);
		assertThat(job.run(later.minus(Duration.ofHours(23)))).isEqualTo(1);
		assertThat(count(ann, Notification.Type.SHIFT_REMINDER)).isEqualTo(2);

		Instant again = later.plus(Duration.ofDays(2));
		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch(null, null, null, again, null, null, null, null), null);
		service.cancelEvent(lead, event.getId(), null);
		assertThat(job.run(again.minus(Duration.ofHours(23)))).isZero();
		assertThat(count(ann, Notification.Type.SHIFT_REMINDER)).isEqualTo(2);
	}

	@Test
	@DisplayName("with the module off the job sends nothing, also when it was switched off at runtime")
	void moduleOff() {
		Shift shift = shift(event(), base, 2, 3, null);
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		ServerSettings current = settings.get();
		ServerSettings.Shifts off = new ServerSettings.Shifts();
		off.setEnabled(false);
		current.setShifts(off);
		settings.save(current);
		try {
			assertThat(job.run(base.minus(Duration.ofHours(23)))).isZero();
			assertThat(count(ann, Notification.Type.SHIFT_REMINDER)).isZero();
		}
		finally {
			current = settings.get();
			current.setShifts(null);
			settings.save(current);
		}
		assertThat(job.run(base.minus(Duration.ofHours(23)))).isEqualTo(1);
	}

	// --- channels ------------------------------------------------------------------------

	@Test
	@DisplayName("the push says nothing about shift, event, place or time; shifts off silences mail and push")
	void pushIsGenericAndSwitchable() {
		NotificationPreferences quiet = prefs();
		quiet.getEvents().put(NotificationPreferences.SHIFTS, new NotificationPreferences.Channel(false, false));
		bob.setNotificationPreferences(quiet);
		bob = users.save(bob);
		ShiftEvent event = event();
		Shift shift = shift(event, base, 2, 3, null);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		assignments.assign(lead, shift.getId(), bob.getId(), false);
		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch(null, null, null,
				base.plus(Duration.ofDays(1)), null, null, null, null), null);
		service.cancelEvent(lead, event.getId(), null);

		List<Invocation> pushes = mockingDetails(push).getInvocations().stream()
				.filter(call -> call.getMethod().getName().equals("sendToUser")).toList();
		Set<String> pushedTo = new HashSet<>();
		for (Invocation sent : pushes) {
			pushedTo.add(sent.getArgument(0));
			String text = sent.getArgument(1) + " " + sent.getArgument(2);
			assertThat(text).doesNotContain("Aufbau", "Sommerfest", "Festwiese").doesNotContainPattern("\\d");
		}
		assertThat(pushedTo).containsExactly(ann.getId());
		assertThat(pushes).hasSize(3);
		assertThat(count(bob, null)).isEqualTo(3); // the bell keeps them
		assertThat(mockingDetails(mail).getInvocations().stream()
				.filter(call -> call.getMethod().getName().equals("sendNotification"))
				.map(call -> (String) call.getArgument(0))).containsOnly(ann.getEmail());
	}

	// --- helpers -------------------------------------------------------------------------

	private long count(User user, Notification.Type type) {
		Criteria criteria = user == null ? new Criteria() : Criteria.where("userId").is(user.getId());
		if (type != null) {
			criteria = criteria.and("type").is(type);
		}
		return mongo.count(Query.query(criteria), Notification.class);
	}

	private Notification one(User user, Notification.Type type) {
		return mongo.findOne(Query.query(Criteria.where("userId").is(user.getId()).and("type").is(type)),
				Notification.class);
	}

	@SafeVarargs
	private List<Integer> parallel(java.util.concurrent.Callable<Integer>... calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.length);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (java.util.concurrent.Callable<Integer> call : calls) {
			futures.add(pool.submit(() -> {
				go.await();
				return call.call();
			}));
		}
		go.countDown();
		List<Integer> results = new ArrayList<>();
		for (Future<Integer> future : futures) {
			results.add(future.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return results;
	}

	private ShiftEvent event() {
		return service.createEvent(lead, project.getId(), new ShiftService.EventDraft("Sommerfest", null, "Festwiese",
				base, base.plus(Duration.ofHours(12)), "Europe/Berlin", true));
	}

	private Shift shift(ShiftEvent event, Instant start, long hours, int required, Integer max) {
		return service.createShift(lead, event.getId(), new ShiftService.ShiftDraft("Aufbau", start,
				start.plus(Duration.ofHours(hours)), required, max), null);
	}

	/** Mail and push at any hour, so the test does not depend on the weekday it runs on. */
	private static NotificationPreferences prefs() {
		NotificationPreferences prefs = NotificationPreferences.defaults();
		prefs.setSchedule(NotificationPreferences.Schedule.ALWAYS);
		return prefs;
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@notices.test")
				.displayName(Character.toUpperCase(name.charAt(0)) + name.substring(1)).locale("de")
				.notificationPreferences(prefs())
				.roles(new HashSet<>(Set.of(Role.MEMBER))).active(true).build());
	}

	/** Lets moving up and the short notice finish on the follow-up thread. */
	private void settle() {
		ShiftFollowUpsSettled.settle(followUps);
	}
}
