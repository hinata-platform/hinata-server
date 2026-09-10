package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.config.HinataProperties;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The lock date, after R9: only the past is ever closed, and no freeze is a dead
 * end.
 *
 * <p>The two failures this file exists to prevent are the two that HIN-88 found in
 * the implementation it inherited. A lock date set in the <em>future</em> freezes
 * today and tomorrow — and because the write gate is shared, it does not disable
 * one feature but every way of recording time there is, present and future
 * (HIN-93 import, HIN-94 calendar takeover, HIN-95 shared entries, HIN-97 MCP).
 * That is the one place the implementation could contradict the obligation it is
 * built around: EuGH 14.05.2019 – C-55/18 <i>CCOO</i>, BAG 13.09.2022 –
 * 1 ABR 22/21 and § 16 Abs. 2 ArbZG require a system with which actual working
 * time <em>can</em> be recorded. And a closed period with no proportionate way
 * back collides with Art. 16 DSGVO, because working time is personal data and
 * inaccurate personal data has to be correctable without undue delay.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimeLockIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeLockIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
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
	private TimeLocks locks;
	@Autowired
	private TimeTrackingSettings policy;
	@Autowired
	private TimeTrackingSettingsGuard guard;
	@Autowired
	private TimeLockExceptionController lockExceptions;
	@Autowired
	private ProjectTimeSettingsController projectTimeSettings;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private SettingsService settings;
	@Autowired
	private HinataProperties properties;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private com.ahmadre.hinata.setup.AdminSettingsController adminSettings;
	@MockitoBean
	private CurrentUser currentUser;

	private User member;
	private User admin;
	private Project project;
	private Project otherProject;

	@BeforeEach
	void seed() {
		for (String collection : List.of("projects", "teams", "users", "work_items",
				"project_time_settings", "timesheet_approvals", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		properties.getTimeTracking().setLockBefore(null);
		ServerSettings fresh = new ServerSettings();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		fresh.setTimeTracking(block);
		settings.save(fresh);
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		otherProject = projects.save(Project.builder().key("MOB").name("Mobile")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		member = user("member", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
	}

	private User user(String name, Role role) {
		User saved = users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").build());
		for (Project each : List.of(project, otherProject)) {
			each.getMemberIds().add(saved.getId());
			each.getLeadIds().add(saved.getId());
			projects.save(each);
		}
		project = projects.findById(project.getId()).orElseThrow();
		otherProject = projects.findById(otherProject.getId()).orElseThrow();
		return saved;
	}

	private void lockBefore(LocalDate day) {
		ServerSettings current = settings.get();
		current.getTimeTracking().setLockBefore(day);
		settings.save(current);
	}

	private ServerSettings withLockBefore(LocalDate day) {
		ServerSettings proposed = new ServerSettings();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setLockBefore(day);
		proposed.setTimeTracking(block);
		return proposed;
	}

	// --- R9: the freeze never reaches the present ---------------------------------------

	@Test
	void aLockDateInTheFutureIsRefusedAndTodayIsAllowed() {
		assertThatThrownBy(() -> guard.check(settings.get(), withLockBefore(TODAY.plusDays(1))))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.lockDateInFuture");
		// Today is the edge and is allowed: isLocked() is strictly "before", so a
		// lock date of today freezes yesterday and leaves today open. That is
		// exactly the line the rule draws.
		guard.check(settings.get(), withLockBefore(TODAY));
		guard.check(settings.get(), withLockBefore(null));
	}

	@Test
	void todaysHoursCanStillBeRecordedWithTheFreezeSetAsFarForwardAsItGoes() {
		lockBefore(TODAY);

		// The whole point of the rule: the most aggressive freeze an operator can
		// configure still leaves the day that is happening recordable.
		assertThat(timeTracking.create(new TimeTrackingService.NewEntry(project.getId(), null, 30,
						TODAY, null, "worked", null, null, List.of(), null),
				WorkItem.Source.APP, member).getDate()).isEqualTo(TODAY);
		// And yesterday is shut, which is what was asked for.
		assertThatThrownBy(() -> timeTracking.create(new TimeTrackingService.NewEntry(
						project.getId(), null, 30, YESTERDAY, null, "worked", null, null,
						List.of(), null), WorkItem.Source.APP, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.locked");
	}

	@Test
	void aFutureLockDateFromTheEnvironmentIsClampedRatherThanRefused() {
		// A deployment variable has nobody to tell. A server that refuses to start,
		// or one that silently stops accepting today's hours, are both worse answers
		// than freezing one day fewer than the variable asked for.
		properties.getTimeTracking().setLockBefore(TODAY.plusYears(1));

		assertThat(policy.lockBefore()).isEqualTo(TODAY);
		assertThat(locks.lockStateFor(member.getId(), project.getId(), TODAY)).isNull();
	}

	// --- R9: the freeze is never a dead end ------------------------------------------------

	@Test
	void anExceptionOpensExactlyItsOwnSpanAndNothingElse() {
		lockBefore(TODAY);
		LocalDate from = LocalDate.of(2026, 9, 2);
		LocalDate to = LocalDate.of(2026, 9, 4);

		TimeLockExceptionController.LockExceptionRequest request =
				new TimeLockExceptionController.LockExceptionRequest();
		request.setFrom(from);
		request.setTo(to);
		request.setNote("Payroll found a missing day");
		asAdmin(() -> lockExceptions.add(request));

		// Both bounds are inside, and the days either side of them are not.
		for (LocalDate open : List.of(from, from.plusDays(1), to)) {
			assertThat(locks.lockStateFor(member.getId(), project.getId(), open))
					.as("%s is reopened", open)
					.isNull();
		}
		for (LocalDate shut : List.of(from.minusDays(1), to.plusDays(1))) {
			assertThat(locks.lockStateFor(member.getId(), project.getId(), shut))
					.as("%s stays frozen", shut)
					.isNotNull();
		}
		// And an entry can actually be written into the opened span — the rule is
		// about the write gate, not only about the resolver.
		assertThat(timeTracking.create(new TimeTrackingService.NewEntry(project.getId(), null, 30,
						from.plusDays(1), null, "corrected", null, null, List.of(), null),
				WorkItem.Source.APP, member).getDate()).isEqualTo(from.plusDays(1));
	}

	@Test
	void anExceptionCarriesItsReasonAndItsAuthorAndIsAudited() {
		lockBefore(TODAY);
		TimeLockExceptionController.LockExceptionRequest request =
				new TimeLockExceptionController.LockExceptionRequest();
		request.setFrom(YESTERDAY);
		request.setTo(YESTERDAY);
		request.setNote("  Corrected a double-counted afternoon  ");

		List<TimeLockExceptionController.LockExceptionResponse> after =
				asAdmin(() -> lockExceptions.add(request));

		assertThat(after).singleElement().satisfies(exception -> {
			assertThat(exception.note()).isEqualTo("Corrected a double-counted afternoon");
			// The server mints these three. A client that could name the author of
			// an audit trail entry is not an audit trail.
			assertThat(exception.by()).isEqualTo(admin.getId());
			assertThat(exception.at()).isEqualTo(NOW);
			assertThat(exception.id()).isNotBlank();
		});
		assertThat(mongo.find(new Query(Criteria.where("action")
				.is(AuditAction.TIME_LOCK_EXCEPTION_ADDED)), AuditLog.class)).hasSize(1);
	}

	@Test
	void removingAnExceptionClosesTheSpanAgainAndIsAuditedToo() {
		lockBefore(TODAY);
		TimeLockExceptionController.LockExceptionRequest request =
				new TimeLockExceptionController.LockExceptionRequest();
		request.setFrom(YESTERDAY);
		request.setTo(YESTERDAY);
		request.setNote("one correction");
		String id = asAdmin(() -> lockExceptions.add(request)).getFirst().id();
		assertThat(locks.lockStateFor(member.getId(), project.getId(), YESTERDAY)).isNull();

		assertThat(asAdmin(() -> lockExceptions.remove(id))).isEmpty();

		assertThat(locks.lockStateFor(member.getId(), project.getId(), YESTERDAY)).isNotNull();
		assertThat(mongo.find(new Query(Criteria.where("action")
				.is(AuditAction.TIME_LOCK_EXCEPTION_REMOVED)), AuditLog.class)).hasSize(1);
		assertThatThrownBy(() -> asAdmin(() -> lockExceptions.remove(id)))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void onlyAnAdministratorOpensASpanInsideTheFreeze() {
		lockBefore(TODAY);
		TimeLockExceptionController.LockExceptionRequest request =
				new TimeLockExceptionController.LockExceptionRequest();
		request.setFrom(YESTERDAY);
		request.setTo(YESTERDAY);
		request.setNote("let me in");

		// A project lead may close their own project's books early; they may not
		// open what an administrator has closed. And this path is not under
		// /api/v1/admin/**, so the check has to be its own.
		assertThatThrownBy(() -> as(member, () -> lockExceptions.add(request)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.lockExceptionsAdminOnly");
	}

	@Test
	void anExceptionNeedsAReasonAndCannotBeUnbounded() {
		lockBefore(TODAY);
		TimeLockExceptionController.LockExceptionRequest inverted =
				new TimeLockExceptionController.LockExceptionRequest();
		inverted.setFrom(YESTERDAY);
		inverted.setTo(YESTERDAY.minusDays(3));
		inverted.setNote("backwards");
		assertThatThrownBy(() -> asAdmin(() -> lockExceptions.add(inverted)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.lockExceptionInvalid");

		TimeLockExceptionController.LockExceptionRequest tooWide =
				new TimeLockExceptionController.LockExceptionRequest();
		tooWide.setFrom(TODAY.minusYears(1));
		tooWide.setTo(TODAY);
		tooWide.setNote("the whole year");
		assertThatThrownBy(() -> asAdmin(() -> lockExceptions.add(tooWide)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodTooLong");
	}

	@Test
	void aHandWrittenExceptionInASettingsPutIsHeldToTheSameShape() {
		// Exceptions arrive through their own route, which mints the author — but
		// the settings PUT is a whole-document write, so the shape is checked rather
		// than trusted.
		ServerSettings proposed = withLockBefore(TODAY);
		ServerSettings.TimeTracking.LockException noReason =
				new ServerSettings.TimeTracking.LockException();
		noReason.setFrom(YESTERDAY);
		noReason.setTo(YESTERDAY);
		proposed.getTimeTracking().setLockExceptions(List.of(noReason));

		assertThatThrownBy(() -> guard.check(settings.get(), proposed))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.lockExceptionNoteRequired");
	}

	@Test
	void theSettingsPutCannotAuthorRemoveOrBackdateAnException() {
		// The one thing in the settings block that is an *event* rather than a
		// setting. A whole-document PUT could otherwise mint one with a hand-written
		// author and timestamp, or delete one, and leave nothing behind but a generic
		// SETTINGS_CHANGED — a reopened payroll month attributed to a colleague, with
		// no span and no reason in the log.
		lockBefore(TODAY);
		TimeLockExceptionController.LockExceptionRequest request =
				new TimeLockExceptionController.LockExceptionRequest();
		request.setFrom(YESTERDAY);
		request.setTo(YESTERDAY);
		request.setNote("the real one");
		String realId = asAdmin(() -> lockExceptions.add(request)).getFirst().id();

		ServerSettings.TimeTracking.LockException forged =
				new ServerSettings.TimeTracking.LockException();
		forged.setId("11111111-1111-1111-1111-111111111111");
		forged.setFrom(TODAY.minusDays(5));
		forged.setTo(TODAY.minusDays(2));
		forged.setNote("Routine");
		forged.setBy(member.getId());
		forged.setAt(NOW.minusSeconds(60 * 60 * 24 * 200));
		ServerSettings proposed = withLockBefore(TODAY);
		proposed.getTimeTracking().setLockExceptions(List.of(forged));

		as(admin, () -> adminSettings.update(proposed));

		// Neither authored nor removed: the stored list is what stands.
		assertThat(policy.lockExceptions()).singleElement().satisfies(stored -> {
			assertThat(stored.getId()).isEqualTo(realId);
			assertThat(stored.getNote()).isEqualTo("the real one");
			assertThat(stored.getBy()).isEqualTo(admin.getId());
			assertThat(stored.getAt()).isEqualTo(NOW);
		});
		assertThat(locks.reopenedByException(TODAY.minusDays(3)))
				.as("the forged span never opened")
				.isFalse();
	}

	@Test
	void anExceptionDoesNotReachIntoAnApprovalsFreeze() {
		// Two mechanisms, one vocabulary — but not one switch. An administrator
		// opening a span in the archive must not thereby unfreeze a period somebody
		// has handed in: that one is the approver's to reopen.
		mongo.save(TimesheetApproval.builder()
				.userId(member.getId()).projectId(project.getId())
				.periodStart(YESTERDAY).periodEnd(YESTERDAY)
				.periodType(TimePolicy.ApprovalPeriod.MONTHLY)
				.status(TimesheetApproval.Status.APPROVED).build());
		ServerSettings current = settings.get();
		current.getTimeTracking().setApprovalsEnabled(true);
		current.getTimeTracking().setLockBefore(TODAY);
		settings.save(current);
		TimeLockExceptionController.LockExceptionRequest request =
				new TimeLockExceptionController.LockExceptionRequest();
		request.setFrom(YESTERDAY);
		request.setTo(YESTERDAY);
		request.setNote("archive correction");
		asAdmin(() -> lockExceptions.add(request));

		TimeLocks.LockState state =
				locks.lockStateFor(member.getId(), project.getId(), YESTERDAY);
		assertThat(state).isNotNull();
		assertThat(state.reason()).isEqualTo(TimePolicy.LockReason.APPROVAL);
	}

	// --- per-project -----------------------------------------------------------------------

	@Test
	void aProjectCanCloseItsOwnBooksEarlierWhileTheRestOfTheInstanceRunsOn() {
		LocalDate projectLock = LocalDate.of(2026, 9, 8);
		ProjectTimeSettingsController.ProjectTimeSettingsRequest request =
				new ProjectTimeSettingsController.ProjectTimeSettingsRequest();
		request.setLockBefore(projectLock);
		as(admin, () -> projectTimeSettings.put(project.getId(), request));

		assertThat(locks.lockBefore(project.getId())).isEqualTo(projectLock);
		// And only that project: without an override the instance decides, which
		// here is "nothing is frozen".
		assertThat(locks.lockBefore(otherProject.getId())).isNull();
		assertThat(locks.lockStateFor(member.getId(), project.getId(),
				projectLock.minusDays(1))).isNotNull();
		assertThat(locks.lockStateFor(member.getId(), otherProject.getId(),
				projectLock.minusDays(1))).isNull();
	}

	@Test
	void aProjectOverrideCanOnlyCloseMoreThanTheInstanceAndNeverLess() {
		// A lead may set this field. Letting it name an *earlier* date would hand
		// them the power to reopen the month an administrator archived, and lead is
		// not administrator — opening a closed span is an exception, and that is
		// admin-only.
		lockBefore(LocalDate.of(2026, 9, 8));
		ProjectTimeSettingsController.ProjectTimeSettingsRequest request =
				new ProjectTimeSettingsController.ProjectTimeSettingsRequest();
		request.setLockBefore(LocalDate.of(2026, 8, 1));
		as(admin, () -> projectTimeSettings.put(project.getId(), request));

		assertThat(locks.lockBefore(project.getId())).isEqualTo(LocalDate.of(2026, 9, 8));
	}

	@Test
	void aProjectLockDateInTheFutureIsRefusedForTheSameReasonTheInstancesIs() {
		ProjectTimeSettingsController.ProjectTimeSettingsRequest request =
				new ProjectTimeSettingsController.ProjectTimeSettingsRequest();
		request.setLockBefore(TODAY.plusDays(1));

		assertThatThrownBy(() -> as(admin, () -> projectTimeSettings.put(project.getId(), request)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.lockDateInFuture");
	}

	// --- the module switch -------------------------------------------------------------------

	@Test
	void withTheModuleOffNothingIsFrozenAtAll() {
		lockBefore(TODAY);
		ServerSettings current = settings.get();
		current.getTimeTracking().setAdvancedEnabled(false);
		settings.save(current);

		// The frozen published app writes through the same service on the ungated
		// 1.x routes. A deployment that set a lock date and never switched the
		// module on must not start refusing it.
		assertThat(locks.lockBefore(project.getId())).isNull();
		assertThat(locks.lockStateFor(member.getId(), project.getId(), YESTERDAY)).isNull();
		assertThat(locks.exceptions()).isEmpty();
	}

	// --- plumbing --------------------------------------------------------------------------

	private <T> T asAdmin(Supplier<T> action) {
		return as(admin, action);
	}

	/**
	 * Runs a controller call as somebody.
	 *
	 * <p>The controllers are exercised rather than the services behind them, because
	 * two of the rules here <em>are</em> the controller's: the lock-exception routes
	 * sit under {@code /api/v1/time} so that they are gated with the module, which
	 * means {@code SecurityConfig}'s blanket rule for {@code /api/v1/admin/**} does
	 * not cover them and the admin check has to be its own. A test that called a
	 * service would not see that line at all.
	 *
	 * <p>{@link CurrentUser} is stubbed rather than a token being forged: resolving
	 * a real JWT would test {@code TokenService}, which has its own suite, and would
	 * make every assertion here depend on it.
	 */
	private <T> T as(User who, Supplier<T> action) {
		when(currentUser.require()).thenReturn(who);
		return action.get();
	}

	private void as(User who, Runnable action) {
		as(who, () -> {
			action.run();
			return null;
		});
	}
}
