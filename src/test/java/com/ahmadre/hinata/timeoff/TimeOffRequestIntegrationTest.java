package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.availability.TimeOffRepository;
import com.ahmadre.hinata.availability.TimeOffService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asking for time off against a real database: what an approval writes, what nothing can refuse,
 * and who is never allowed to decide.
 *
 * <p>The ticket's acceptance list is this list. The four that carry the most: nobody decides their
 * own request — administrators included and in both directions; a decision made twice books once;
 * a sick report has no path through the server that can say no; and sickness inside approved leave
 * gives the days back (§ 9 BUrlG).
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimeOffRequestIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeOffRequestIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	/** Monday, 15 June 2026. Everything below counts weeks from a Monday so the maths is readable. */
	static final Instant NOW = Instant.parse("2026-06-15T09:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
	private static final int YEAR = 2026;
	private static final int DAY = TimeOffType.DAY;

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
	private TeamRepository teams;
	@Autowired
	private TimeOffRequestService requests;
	@Autowired
	private TimeOffRequestRepository requestRepository;
	@Autowired
	private TimeOffBalanceService balances;
	@Autowired
	private TimeOffTypeService types;
	@Autowired
	private TimeOffTypeRepository typeRepository;
	@Autowired
	private TimeOffLedgerRepository ledger;
	@Autowired
	private TimeOffRepository absences;
	@Autowired
	private TimeOffService availability;

	private User member;
	private User admin;
	private User secondAdmin;
	private User keeper;
	private TimeOffType vacation;
	private TimeOffType sick;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "time_off_types", "time_off_ledger",
				"time_off_entitlements", "time_off_employment", "time_off_requests", "time_off",
				"working_schedules", "holidays", "notifications", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = user("member", Role.MEMBER);
		keeper = user("keeper", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
		secondAdmin = user("admin2", Role.ADMIN);
		enableModule(List.of(keeper.getId()));
		vacation = approvable(types.byKey(TimeOffType.SYSTEM_VACATION).orElseThrow());
		sick = types.byKey(TimeOffType.SYSTEM_SICK).orElseThrow();
		balances.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);
		// A1's entitlement notice names the type on purpose — it is the person's own. Cleared so
		// the sweep below reads only what this stage sends.
		mongo.getCollection("notifications").deleteMany(new Document());
	}

	// --- what an approval writes ---------------------------------------------------

	@Test
	@DisplayName("an approval writes exactly one absence and exactly one booking")
	void approvingWritesOneAbsenceAndOneBooking() {
		TimeOffRequest filed = requests.submit(member, week(3));
		assertThat(filed.getStatus()).isEqualTo(TimeOffRequest.Status.SUBMITTED);
		assertThat(filed.milliDays()).isEqualTo(5 * DAY);
		// Nothing is written before somebody decides: a plan is not an absence.
		assertThat(absences.count()).isZero();

		TimeOffRequest approved = requests.approve(filed.getId(), null, admin);

		assertThat(approved.getStatus()).isEqualTo(TimeOffRequest.Status.APPROVED);
		assertThat(absences.count()).isEqualTo(1);
		assertThat(approved.getTimeOffId()).isNotNull();
		List<TimeOffLedgerEntry> booked = bookingsOf(member);
		assertThat(booked).hasSize(1);
		assertThat(booked.getFirst().milliDays()).isEqualTo(-5 * DAY);
		assertThat(approved.getLedgerId()).isEqualTo(booked.getFirst().getId());
	}

	@Test
	@DisplayName("a decision made twice at once books once")
	void approvingTwiceNeverBooksTwice() {
		TimeOffRequest filed = requests.submit(member, week(3));
		requests.approve(filed.getId(), null, admin);

		assertThatThrownBy(() -> requests.approve(filed.getId(), null, secondAdmin))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.requestDecided");

		assertThat(bookingsOf(member)).hasSize(1);
		assertThat(absences.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("two people deciding at the same moment produce one absence and one booking")
	void twoConcurrentApprovalsBookOnce() throws Exception {
		TimeOffRequest filed = requests.submit(member, week(3));
		int deciders = 6;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(deciders);
		AtomicInteger approvals = new AtomicInteger();
		AtomicInteger refusals = new AtomicInteger();

		for (int i = 0; i < deciders; i++) {
			// Alternating, so the two administrators really are pressing the same button at the
			// same moment rather than one of them doing it six times.
			User decider = i % 2 == 0 ? admin : secondAdmin;
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					requests.approve(filed.getId(), null, decider);
					approvals.incrementAndGet();
				}
				catch (Exception refused) {
					refusals.incrementAndGet();
				}
				finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

		// The point of the whole exercise. Before the claim moved ahead of the writes, every
		// thread passed the status check, every thread entered an absence and booked the days,
		// and only the saves raced — so a week off cost six weeks of balance.
		assertThat(approvals.get()).isEqualTo(1);
		assertThat(refusals.get()).isEqualTo(deciders - 1);
		assertThat(bookingsOf(member)).hasSize(1);
		assertThat(absences.count()).isEqualTo(1);
		assertThat(remaining()).isEqualTo(15 * DAY);
	}

	@Test
	@DisplayName("a claim that cannot be written is put back, not left standing")
	void aFailedWriteLeavesTheRequestWaiting() {
		TimeOffRequest filed = requests.submit(member, week(3));
		// An absence more than two years out is refused by the calendar itself, which is the
		// realistic way the write after the claim fails.
		TimeOffRequest far = requestRepository.findById(filed.getId()).orElseThrow();
		far.setFrom(TODAY.plusYears(4));
		far.setTo(TODAY.plusYears(4).plusDays(4));
		requestRepository.save(far);

		assertThatThrownBy(() -> requests.approve(filed.getId(), null, admin))
				.isInstanceOf(ApiException.class);

		TimeOffRequest after = requestRepository.findById(filed.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(TimeOffRequest.Status.SUBMITTED);
		assertThat(after.getDecidedBy()).isNull();
		assertThat(after.getTimeOffId()).isNull();
		// And the step that did not happen is not in the story of the request.
		assertThat(after.getHistory()).extracting(TimeOffRequest.Event::getTo)
				.containsExactly(TimeOffRequest.Status.SUBMITTED);
		assertThat(bookingsOf(member)).isEmpty();
		assertThat(absences.count()).isZero();
	}

	@Test
	@DisplayName("a request nobody can be routed to still lands with the administrators")
	void anAutomaticTypeStillNamesAnAudience() {
		vacation.setApproverRule(TimeOffType.ApproverRule.AUTO);
		typeRepository.save(vacation);

		TimeOffRequest filed = requests.submit(member, week(3));

		// The routing answers nobody for AUTO on purpose, but what is stored may not be nobody:
		// an automatic approval that failed would otherwise leave a request no inbox can find.
		assertThat(filed.getApproverIds()).containsExactlyInAnyOrder(admin.getId(), secondAdmin.getId());
		assertThat(filed.getStatus()).isEqualTo(TimeOffRequest.Status.APPROVED);
	}

	@Test
	@DisplayName("a cancellation takes back both the absence and the days")
	void cancellingReversesExactlyWhatApprovingDid() {
		TimeOffRequest filed = requests.submit(member, week(3));
		requests.approve(filed.getId(), null, admin);

		TimeOffRequest cancelled = requests.cancel(filed.getId(), "Plans changed", member);

		assertThat(cancelled.getStatus()).isEqualTo(TimeOffRequest.Status.CANCELLED);
		assertThat(absences.count()).isZero();
		assertThat(cancelled.getTimeOffId()).isNull();
		// A counter-booking, never the removal of a row: the journal is append-only.
		List<TimeOffLedgerEntry> rows = bookingsOf(member);
		assertThat(rows).hasSize(2);
		assertThat(rows.stream().mapToInt(TimeOffLedgerEntry::milliDays).sum()).isZero();
		assertThat(remaining()).isEqualTo(20 * DAY);
	}

	@Test
	@DisplayName("leave that has begun takes a keeper to cancel")
	void thePersonCannotCancelLeaveTheyHaveStarted() {
		TimeOffRequest filed = requests.submit(member, draft(TODAY.minusDays(2), TODAY.plusDays(2)));
		requests.approve(filed.getId(), null, admin);

		assertThatThrownBy(() -> requests.cancel(filed.getId(), null, member))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.cancelNotYours");

		assertThat(requests.cancel(filed.getId(), "Entered by mistake", keeper).getStatus())
				.isEqualTo(TimeOffRequest.Status.CANCELLED);
	}

	// --- who may decide -------------------------------------------------------------

	@Test
	@DisplayName("nobody decides their own request — an administrator included, in both directions")
	void nobodyDecidesTheirOwn() {
		// An administrator asks. The rule routes to the administrators, so they would be among
		// their own audience; they are struck out of it when it is worked out.
		TimeOffRequest theirs = requests.submit(admin, week(3));
		assertThat(theirs.getApproverIds()).doesNotContain(admin.getId());
		assertThat(theirs.getApproverIds()).contains(secondAdmin.getId());

		assertThatThrownBy(() -> requests.approve(theirs.getId(), null, admin))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.decideOwn");
		assertThatThrownBy(() -> requests.reject(theirs.getId(), "no", admin))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.decideOwn");

		// The second guard holds even against a list that names them: keeping absences for the
		// organisation is not keeping them for oneself.
		TimeOffRequest forced = requestRepository.findById(theirs.getId()).orElseThrow();
		forced.setApproverIds(new java.util.ArrayList<>(List.of(admin.getId())));
		requestRepository.save(forced);
		assertThatThrownBy(() -> requests.approve(theirs.getId(), null, admin))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.decideOwn");
	}

	@Test
	@DisplayName("with nobody to ask, a request lands with the administrators rather than nowhere")
	void anEmptyAudienceFallsBackToTheAdministrators() {
		// Nobody is on a team, so the team-lead rule finds nobody at all.
		vacation.setApproverRule(TimeOffType.ApproverRule.TEAM_LEAD);
		typeRepository.save(vacation);

		TimeOffRequest filed = requests.submit(member, week(3));

		assertThat(filed.getApproverIds()).containsExactlyInAnyOrder(admin.getId(), secondAdmin.getId());
		assertThat(filed.getStatus()).isEqualTo(TimeOffRequest.Status.SUBMITTED);
	}

	@Test
	@DisplayName("a team anybody could have made does not make its maker an approver")
	void onlyASanctionedTeamCarriesApprovalWeight() {
		vacation.setApproverRule(TimeOffType.ApproverRule.TEAM_LEAD);
		typeRepository.save(vacation);
		// Any user may make a team and add anybody to it without being asked. If that were enough,
		// this is all it would take to become the person who decides somebody else's leave.
		User outsider = user("mallory", Role.MEMBER);
		teams.save(Team.builder().key("SELF").name("Self made").createdBy(outsider.getId())
				.members(List.of(
						TeamMembership.builder().userId(outsider.getId()).role(TeamRole.ADMIN).build(),
						TeamMembership.builder().userId(member.getId()).role(TeamRole.MEMBER).build()))
				.build());

		TimeOffRequest filed = requests.submit(member, week(3));

		assertThat(filed.getApproverIds()).doesNotContain(outsider.getId());
		assertThat(filed.getApproverIds()).containsExactlyInAnyOrder(admin.getId(), secondAdmin.getId());
		assertThatThrownBy(() -> requests.approve(filed.getId(), null, outsider))
				.isInstanceOf(ApiException.class);

		// The same team, set up by an administrator, does carry the weight.
		teams.deleteAll();
		teams.save(Team.builder().key("REAL").name("Kept by the office").createdBy(admin.getId())
				.members(List.of(
						TeamMembership.builder().userId(outsider.getId()).role(TeamRole.ADMIN).build(),
						TeamMembership.builder().userId(member.getId()).role(TeamRole.MEMBER).build()))
				.build());

		assertThat(requests.submit(member, week(4)).getApproverIds()).containsExactly(outsider.getId());
	}

	@Test
	@DisplayName("a rejection without a reason is refused (§ 7 Abs. 1 BUrlG)")
	void rejectingNeedsAReason() {
		TimeOffRequest filed = requests.submit(member, week(3));

		assertThatThrownBy(() -> requests.reject(filed.getId(), "   ", admin))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.decisionReasonRequired");

		TimeOffRequest rejected = requests.reject(filed.getId(), "The whole group is away that week", admin);
		assertThat(rejected.getStatus()).isEqualTo(TimeOffRequest.Status.REJECTED);
		// The reason reaches the person: it is on the request, which is theirs to read.
		assertThat(rejected.getDecisionNote()).isEqualTo("The whole group is away that week");
		assertThat(bookingsOf(member)).isEmpty();
	}

	@Test
	@DisplayName("a type nobody has to approve is decided as it arrives, and says so")
	void automaticApprovalTellsThePersonAndIsInTheHistory() {
		vacation.setApproverRule(TimeOffType.ApproverRule.AUTO);
		typeRepository.save(vacation);

		TimeOffRequest filed = requests.submit(member, week(3));

		assertThat(filed.getStatus()).isEqualTo(TimeOffRequest.Status.APPROVED);
		assertThat(filed.getHistory()).extracting(TimeOffRequest.Event::getTo)
				.containsExactly(TimeOffRequest.Status.SUBMITTED, TimeOffRequest.Status.APPROVED);
		assertThat(bellOf(member)).extracting(Notification::getType)
				.contains(Notification.Type.TIME_OFF_AUTO_APPROVED);
	}

	// --- the balance ------------------------------------------------------------------

	@Test
	@DisplayName("a balance that will not cover it refuses with the vocabulary the client knows")
	void tooFewDaysLeftIsRefusedWithReasonHolderAndRemedy() {
		// Twenty days granted; ask for twenty-five working days across five weeks.
		TimeOffRequest filed = requests.submit(member, draft(monday(3), monday(3).plusDays(4 + 28)));
		assertThat(filed.milliDays()).isEqualTo(25 * DAY);

		ApiException refusal = org.assertj.core.api.Assertions
				.catchThrowableOfType(ApiException.class, () -> requests.approve(filed.getId(), null, admin));

		assertThat(refusal.getMessageKey()).isEqualTo("error.timeOff.balanceExceeded");
		assertThat(refusal.getDetails()).containsEntry("reason", "balanceExceeded")
				.containsEntry("holder", "keeper").containsEntry("remedy", "grant");
		// Nothing was half-written: the request is still waiting for somebody to decide it.
		assertThat(requestRepository.findById(filed.getId()).orElseThrow().getStatus())
				.isEqualTo(TimeOffRequest.Status.SUBMITTED);
		assertThat(absences.count()).isZero();
	}

	@Test
	@DisplayName("a type that allows a negative balance lets it through, down to the floor it names")
	void aNegativeBalanceIsAllowedAsFarAsTheTypeSaysAndNoFurther() {
		vacation.setNegativeBalanceAllowed(true);
		vacation.setNegativeLimitMilliDays(10 * DAY);
		typeRepository.save(vacation);

		TimeOffRequest within = requests.submit(member, draft(monday(3), monday(3).plusDays(4 + 28)));
		assertThat(requests.approve(within.getId(), null, admin).getStatus())
				.isEqualTo(TimeOffRequest.Status.APPROVED);
		assertThat(remaining()).isEqualTo(-5 * DAY);

		TimeOffRequest beyond = requests.submit(member, draft(monday(12), monday(12).plusDays(4 + 21)));
		assertThatThrownBy(() -> requests.approve(beyond.getId(), null, admin))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.balanceExceeded");
	}

	@Test
	@DisplayName("a pattern that changes later does not reinterpret a decided request")
	void theDayAmountIsFrozenWhenItIsFiled() {
		TimeOffRequest filed = requests.submit(member, week(3));
		requests.approve(filed.getId(), null, admin);
		assertThat(remaining()).isEqualTo(15 * DAY);

		// The person moves to four days a week, from today.
		mongo.getCollection("working_schedules").insertOne(new Document()
				.append("userId", member.getId())
				.append("validFrom", java.util.Date.from(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC)))
				.append("minutesPerWeekday", List.of(480, 480, 480, 480, 0, 0, 0))
				.append("createdBy", member.getId())
				.append("createdAt", java.util.Date.from(NOW)));

		assertThat(requestRepository.findById(filed.getId()).orElseThrow().milliDays())
				.isEqualTo(5 * DAY);
		assertThat(remaining()).isEqualTo(15 * DAY);
	}

	// --- sickness ------------------------------------------------------------------

	@Test
	@DisplayName("there is no path through the server that refuses a sick report")
	void sicknessIsReportedAndNothingCanSayNo() {
		// Retroactive, no reason, no approver, no balance behind it — and the type is one nobody
		// may apply for: § 5 EFZG knows a notification, not a permission (R11).
		TimeOffRequestService.Sick reported =
				requests.reportSick(member, TODAY.minusDays(5), TODAY.minusDays(3), false, null);

		assertThat(reported.absenceId()).isNotNull();
		assertThat(reported.milliDays()).isEqualTo(3 * DAY);
		assertThat(absences.count()).isEqualTo(1);
		// A sick type carries no quota, so nothing was taken off a balance (§ 5 EFZG).
		assertThat(reported.ledgerId()).isNull();
		assertThat(remaining()).isEqualTo(20 * DAY);
		assertThat(sick.requiresApproval()).isFalse();

		assertThatThrownBy(() -> requests.submit(member, new TimeOffRequestService.Draft(
				sick.getId(), monday(3), monday(3), null, null, null, null)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.sickNotRequested");
	}

	@Test
	@DisplayName("§ 9 BUrlG: sickness inside approved leave gives the days back and shortens it")
	void sicknessDuringLeaveHandsTheDaysBack() {
		// Leave next week, Monday to Friday, approved.
		TimeOffRequest filed = requests.submit(member, draft(monday(1), monday(1).plusDays(4)));
		requests.approve(filed.getId(), null, admin);
		assertThat(remaining()).isEqualTo(15 * DAY);

		// Ill from the Wednesday to the Friday of that week.
		TimeOffRequestService.Sick reported =
				requests.reportSick(member, monday(1).plusDays(2), monday(1).plusDays(4), false, null);

		assertThat(reported.returnedMilliDays()).isEqualTo(3 * DAY);
		assertThat(remaining()).isEqualTo(18 * DAY);

		// The leave is shortened rather than erased: Monday and Tuesday were leave and stay leave.
		TimeOffRequest shortened = requestRepository.findById(filed.getId()).orElseThrow();
		assertThat(shortened.getTo()).isEqualTo(monday(1).plusDays(1));
		assertThat(shortened.getStatus()).isEqualTo(TimeOffRequest.Status.APPROVED);
		TimeOff leave = absences.findById(shortened.getTimeOffId()).orElseThrow();
		assertThat(leave.getTo()).isEqualTo(monday(1).plusDays(1));
	}

	// --- the older road --------------------------------------------------------------

	@Test
	@DisplayName("entering an approvable type directly answers 409 and says what to do instead")
	void theDirectEntryPathClosesForAnApprovableType() {
		ApiException refusal = org.assertj.core.api.Assertions.catchThrowableOfType(ApiException.class,
				() -> availability.create(member, new TimeOffService.Draft(member.getId(), null,
						vacation.getId(), monday(3), monday(3).plusDays(4), null, null)));

		assertThat(refusal.getMessageKey()).isEqualTo("error.timeOff.approvalRequired");
		assertThat(refusal.getDetails()).containsEntry("reason", "approvalRequired")
				.containsEntry("remedy", "request");

		// A keeper enters absences for other people as part of the job, so the road stays open.
		assertThat(availability.create(keeper, new TimeOffService.Draft(member.getId(), null,
				vacation.getId(), monday(3), monday(3).plusDays(4), null, null))).isNotNull();
	}

	@Test
	@DisplayName("filtering for a built-in type finds the absences entered before the catalogue")
	void aBuiltInTypeFindsTheRowsThatNameNoType() {
		// What an instance holds after switching absence management on: rows written by the older
		// screen, which knew three kinds and no ids, beside rows entered under a type.
		absences.save(TimeOff.builder().userId(member.getId()).type(TimeOff.Type.VACATION)
				.from(monday(1)).to(monday(1).plusDays(2)).build());
		vacation.setApprovalRequired(false);
		typeRepository.save(vacation);
		availability.create(member, new TimeOffService.Draft(member.getId(), null, vacation.getId(),
				monday(3), monday(3).plusDays(4), null, null));

		TimeOffService.Listing leave = availability.page(member, member.getId(), null, null,
				new TimeOffService.Filter(null, vacation.getId(), null, false), 0, 50);

		assertThat(leave.page().getContent()).as("both are leave, whether or not they name the type")
				.hasSize(2);

		// A type an operator invented has no such history, so it stays exact: the old rows are not
		// "parental leave" just because that type is stored as OTHER too.
		TimeOffType parental = typeRepository.save(TimeOffType.builder().key("parental")
				.kind(TimeOffType.Kind.PARENTAL).active(true).build());
		absences.save(TimeOff.builder().userId(member.getId()).type(TimeOff.Type.OTHER)
				.from(monday(5)).to(monday(5)).build());

		assertThat(availability.page(member, member.getId(), null, null,
				new TimeOffService.Filter(null, parental.getId(), null, false), 0, 50)
				.page().getContent()).isEmpty();
	}

	@Test
	@DisplayName("a type nobody has to approve is still entered the old way")
	void theDirectEntryPathStaysOpenForEverythingElse() {
		vacation.setApprovalRequired(false);
		typeRepository.save(vacation);

		assertThat(availability.create(member, new TimeOffService.Draft(member.getId(), null,
				vacation.getId(), monday(3), monday(3).plusDays(4), null, null))).isNotNull();
	}

	@Test
	@DisplayName("vacation sent without a type id is still vacation, and still needs approving")
	void theDirectEntryPathKnowsVacationWithoutItsId() {
		// A client that never heard of the catalogue sends the plain type only. That used to pass
		// the gate as "no type, nothing to check" — the way round every approval there was.
		ApiException refusal = org.assertj.core.api.Assertions.catchThrowableOfType(ApiException.class,
				() -> availability.create(member, new TimeOffService.Draft(member.getId(),
						TimeOff.Type.VACATION, null, monday(3), monday(3).plusDays(4), null, null)));

		assertThat(refusal.getMessageKey()).isEqualTo("error.timeOff.approvalRequired");
		// Retyping an absence into vacation without naming the id is the same walk-around.
		TimeOff other = availability.create(member, new TimeOffService.Draft(member.getId(),
				TimeOff.Type.OTHER, null, monday(4), monday(4), null, null));
		assertThatThrownBy(() -> availability.update(member, other.getId(),
				new TimeOffService.Patch(TimeOff.Type.VACATION, null, null, null, null, null)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.approvalRequired");
	}

	@Test
	@DisplayName("§ 9 BUrlG: half a day of leave gives half a day back, not a whole one")
	void sicknessGivesBackWhatTheLeaveCost() {
		// Half a day off next Monday: 500 milliDays, not 1000.
		TimeOffRequest filed = requests.submit(member, new TimeOffRequestService.Draft(
				vacation.getId(), monday(1), monday(1), 500, null, null, null));
		requests.approve(filed.getId(), null, admin);
		assertThat(filed.milliDays()).isEqualTo(DAY / 2);
		assertThat(remaining()).isEqualTo(20 * DAY - DAY / 2);

		TimeOffRequestService.Sick reported = requests.reportSick(member, monday(1), monday(1), false, null);

		// Exactly what it cost comes back. Counting the sick day at full rate would hand the
		// person half a day of leave they never had — twenty-five cycles a day of free vacation.
		assertThat(reported.returnedMilliDays()).isEqualTo(DAY / 2);
		assertThat(remaining()).isEqualTo(20 * DAY);
	}

	@Test
	@DisplayName("§ 9 BUrlG: sickness in the middle takes the rest of the leave with it")
	void sicknessInsideLeaveReturnsEverythingBehindIt() {
		TimeOffRequest filed = requests.submit(member, draft(monday(1), monday(1).plusDays(4)));
		requests.approve(filed.getId(), null, admin);
		assertThat(remaining()).isEqualTo(15 * DAY);

		// Ill on the Wednesday alone. Thursday and Friday were leave behind it; the leave is cut
		// at the Tuesday, so those two days go back rather than staying booked on days off
		// nobody is taking.
		TimeOffRequestService.Sick reported =
				requests.reportSick(member, monday(1).plusDays(2), monday(1).plusDays(2), false, null);

		assertThat(reported.returnedMilliDays()).isEqualTo(3 * DAY);
		assertThat(remaining()).isEqualTo(18 * DAY);
		TimeOffRequest shortened = requestRepository.findById(filed.getId()).orElseThrow();
		assertThat(shortened.getTo()).isEqualTo(monday(1).plusDays(1));
		assertThat(shortened.milliDays()).isEqualTo(2 * DAY);
		assertThat(absences.findById(shortened.getTimeOffId()).orElseThrow().getTo())
				.isEqualTo(monday(1).plusDays(1));
	}

	@Test
	@DisplayName("only the person who asked withdraws, and only while nobody has decided")
	void onlyTheOwnerWithdrawsAWaitingRequest() {
		TimeOffRequest filed = requests.submit(member, week(3));

		// Somebody else's request is not theirs to take back, and not theirs to find either.
		for (User stranger : List.of(keeper, admin)) {
			assertThatThrownBy(() -> requests.withdraw(filed.getId(), stranger))
					.isInstanceOf(ApiException.class)
					.hasMessageContaining("error.notFound");
		}

		TimeOffRequest withdrawn = requests.withdraw(filed.getId(), member);
		assertThat(withdrawn.getStatus()).isEqualTo(TimeOffRequest.Status.WITHDRAWN);
		assertThat(absences.count()).isZero();
		assertThat(remaining()).isEqualTo(20 * DAY);

		// And once it is out of the queue there is nothing left to take back.
		assertThatThrownBy(() -> requests.withdraw(filed.getId(), member))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.requestDecided");
	}

	@Test
	@DisplayName("a day portion outside a day is refused rather than counted")
	void aPortionHasToBePartOfADay() {
		for (Integer portion : java.util.Arrays.asList(0, -1, TimeOffSpan.DAY + 1)) {
			assertThatThrownBy(() -> requests.submit(member, new TimeOffRequestService.Draft(
					vacation.getId(), monday(2), monday(2), portion, null, null, null)))
					.isInstanceOf(ApiException.class)
					.hasMessageContaining("error.timeOff.portionInvalid");
		}
	}

	@Test
	@DisplayName("vacation entered before approval came in is not stretched past it, only given back")
	void anEarlierVacationIsNotStretchedPastTheGate() {
		// Entered by a keeper, which is how vacation gets in once approval is on — and how every
		// vacation got in before it was switched on.
		TimeOff earlier = availability.create(keeper, new TimeOffService.Draft(member.getId(), null,
				vacation.getId(), monday(3), monday(3).plusDays(1), null, null));

		for (TimeOffService.Patch longer : List.of(
				new TimeOffService.Patch(null, null, null, monday(3).plusDays(4), null, null),
				new TimeOffService.Patch(null, null, monday(3).minusDays(3), null, null, null))) {
			ApiException refusal = org.assertj.core.api.Assertions.catchThrowableOfType(ApiException.class,
					() -> availability.update(member, earlier.getId(), longer));
			assertThat(refusal.getMessageKey()).isEqualTo("error.timeOff.approvalRequired");
		}

		// Giving a day back, or changing the note, needs nobody.
		TimeOff shorter = availability.update(member, earlier.getId(),
				new TimeOffService.Patch(null, null, null, monday(3), null, "Just Monday"));
		assertThat(shorter.getTo()).isEqualTo(monday(3));
		assertThat(shorter.getNote()).isEqualTo("Just Monday");
		// A keeper may still stretch it: entering absences for others is the job.
		assertThat(availability.update(keeper, earlier.getId(),
				new TimeOffService.Patch(null, null, null, monday(3).plusDays(2), null, null)).getTo())
				.isEqualTo(monday(3).plusDays(2));
	}

	@Test
	@DisplayName("an absence from a request knows it, and changes only through the request")
	void anAbsenceFromARequestChangesOnlyThroughIt() {
		TimeOffRequest filed = requests.submit(member, week(3));
		TimeOffRequest approved = requests.approve(filed.getId(), null, admin);
		TimeOff absence = absences.findById(approved.getTimeOffId()).orElseThrow();
		assertThat(absence.getRequestId()).isEqualTo(filed.getId());

		// Neither the person nor a keeper may edit or delete it directly: the booking would stay.
		for (User actor : List.of(member, keeper)) {
			ApiException edit = org.assertj.core.api.Assertions.catchThrowableOfType(ApiException.class,
					() -> availability.update(actor, absence.getId(),
							new TimeOffService.Patch(null, null, null, null, null, "moved")));
			assertThat(edit.getMessageKey()).isEqualTo("error.timeOff.requestBacked");
			assertThat(edit.getDetails()).containsEntry("reason", "requestBacked")
					.containsEntry("remedy", "cancelRequest");
			assertThatThrownBy(() -> availability.delete(actor, absence.getId()))
					.isInstanceOf(ApiException.class)
					.hasMessageContaining("error.timeOff.requestBacked");
		}
		assertThat(absences.count()).isEqualTo(1);

		// Cancelling the request is the way, and it takes the absence and the days back.
		requests.cancel(filed.getId(), null, member);
		assertThat(absences.count()).isZero();
		assertThat(remaining()).isEqualTo(20 * DAY);
	}

	// --- editing a request -------------------------------------------------------------

	@Test
	@DisplayName("a waiting request can be edited by its owner, and its days are worked out again")
	void aWaitingRequestIsEditedAndRecounted() {
		TimeOffRequest filed = requests.submit(member, week(3));
		assertThat(filed.milliDays()).isEqualTo(5 * DAY);

		TimeOffRequest edited = requests.edit(filed.getId(), member,
				new TimeOffRequestService.Draft(vacation.getId(), monday(3), monday(3).plusDays(1), null, null,
						"Only two days after all", null));

		assertThat(edited.getStatus()).isEqualTo(TimeOffRequest.Status.SUBMITTED);
		assertThat(edited.milliDays()).isEqualTo(2 * DAY);
		assertThat(edited.getTo()).isEqualTo(monday(3).plusDays(1));
		assertThat(edited.getNote()).isEqualTo("Only two days after all");
		assertThat(edited.getHistory()).last().satisfies(step -> {
			assertThat(step.getFrom()).isEqualTo(TimeOffRequest.Status.SUBMITTED);
			assertThat(step.getTo()).isEqualTo(TimeOffRequest.Status.SUBMITTED);
			assertThat(step.getNote()).isNull();
		});
		// Nothing is written until somebody decides, edit or no edit.
		assertThat(absences.count()).isZero();
		assertThat(requests.approve(filed.getId(), null, admin).milliDays()).isEqualTo(2 * DAY);
		assertThat(bookingsOf(member).getFirst().milliDays()).isEqualTo(-2 * DAY);
	}

	@Test
	@DisplayName("only the owner edits, and only while nobody has decided")
	void onlyTheOwnerEditsAWaitingRequest() {
		TimeOffRequest filed = requests.submit(member, week(3));

		assertThatThrownBy(() -> requests.edit(filed.getId(), admin, week(4)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.notFound");

		requests.approve(filed.getId(), null, admin);
		assertThatThrownBy(() -> requests.edit(filed.getId(), member, week(4)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.requestDecided");
	}

	@Test
	@DisplayName("an edit into a type nobody approves decides itself, like a new request would")
	void anEditIntoAnAutomaticTypeDecidesItself() {
		TimeOffRequest filed = requests.submit(member, week(3));
		vacation.setApprovalRequired(false);
		typeRepository.save(vacation);

		TimeOffRequest edited = requests.edit(filed.getId(), member, week(3));

		assertThat(edited.getStatus()).isEqualTo(TimeOffRequest.Status.APPROVED);
		assertThat(absences.count()).isEqualTo(1);
		assertThat(absences.findById(edited.getTimeOffId()).orElseThrow().getRequestId())
				.isEqualTo(filed.getId());
	}

	@Test
	@DisplayName("an edit is audited without the note")
	void anEditIsAuditedWithoutItsNote() {
		TimeOffRequest filed = requests.submit(member, week(3));
		requests.edit(filed.getId(), member, new TimeOffRequestService.Draft(vacation.getId(), monday(3),
				monday(3).plusDays(2), null, null, "Doctor's appointment moved", null));

		List<AuditLog> edits = mongo.find(Query.query(Criteria.where("action")
				.is(AuditAction.TIME_OFF_REQUEST_EDITED.name())), AuditLog.class);
		assertThat(edits).hasSize(1);
		assertThat(edits.getFirst().getMetadata()).containsKeys("user", "from", "to", "milliDays");
		assertThat(String.valueOf(edits.getFirst().getMetadata())).doesNotContain("Doctor");
	}

	// --- what the messages say --------------------------------------------------------

	@Test
	@DisplayName("no notification about an absence names what kind it is")
	void noNoticeNamesTheTypeOfAbsence() {
		TimeOffRequest filed = requests.submit(member, week(3));
		requests.approve(filed.getId(), null, admin);
		requests.reportSick(member, TODAY.minusDays(5), TODAY.minusDays(3), false, null);

		List<Notification> everything = mongo.findAll(Notification.class);
		assertThat(everything).isNotEmpty();
		for (Notification notice : everything) {
			assertThat(notice.getTitle() + " " + notice.getBody())
					.as("notification %s", notice.getType())
					.doesNotContainIgnoringCase("vacation")
					.doesNotContainIgnoringCase("sick")
					.doesNotContainIgnoringCase("ill");
		}
	}

	@Test
	@DisplayName("the audit trail carries the span and the amount, never a sentence somebody wrote")
	void theAuditTrailKeepsNoFreeText() {
		TimeOffRequest filed = requests.submit(member,
				new TimeOffRequestService.Draft(vacation.getId(), monday(3), monday(3).plusDays(4),
						null, null, "Wedding of a close friend", null));
		requests.reject(filed.getId(), "Two others are already away", admin);

		List<AuditLog> trail = mongo.find(Query.query(Criteria.where("action")
				.in(AuditAction.TIME_OFF_REQUEST_SUBMITTED.name(),
						AuditAction.TIME_OFF_REQUEST_REJECTED.name())), AuditLog.class);
		assertThat(trail).hasSize(2);
		for (AuditLog entry : trail) {
			assertThat(entry.getMetadata()).containsKeys("user", "from", "to", "milliDays");
			assertThat(String.valueOf(entry.getMetadata())).doesNotContain("Wedding");
			assertThat(String.valueOf(entry.getMetadata())).doesNotContain("Two others");
		}
	}

	// --- reading ------------------------------------------------------------------------

	@Test
	@DisplayName("a stranger gets 404 rather than 403, and learns nothing from the difference")
	void aStrangerCannotReadSomebodyElsesRequest() {
		TimeOffRequest filed = requests.submit(member, week(3));
		User stranger = user("stranger", Role.MEMBER);

		assertThatThrownBy(() -> requests.get(filed.getId(), stranger))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.notFound");

		assertThat(requests.get(filed.getId(), member).getId()).isEqualTo(filed.getId());
		assertThat(requests.get(filed.getId(), admin).getId()).isEqualTo(filed.getId());
	}

	@Test
	@DisplayName("the inbox reads what somebody was asked to decide, and their own list what they asked")
	void theTwoListsAnswerTheTwoQuestions() {
		requests.submit(member, week(3));

		assertThat(requests.inbox(admin, null, 0, 25).getContent())
				.extracting(view -> view.request().getUserId()).containsExactly(member.getId());
		assertThat(requests.inbox(member, null, 0, 25).getContent()).isEmpty();
		assertThat(requests.mine(member, null, YEAR, 0, 25).getContent()).hasSize(1);
		assertThat(requests.mine(member, null, YEAR + 1, 0, 25).getContent()).isEmpty();
	}

	@Test
	@DisplayName("a clash line names who is away and never what kind of absence it is")
	void clashesNameNamesAndSpansOnly() {
		requests.submit(member, week(3));
		User colleague = user("colleague", Role.MEMBER);
		balances.grant(keeper, colleague.getId(), vacation.getId(), YEAR, null, null);
		requests.submit(colleague, week(3));

		List<TimeOffRequestService.Clash> clashes =
				requests.conflicts(admin, monday(3), monday(3).plusDays(4));

		assertThat(clashes).hasSize(2);
		assertThat(clashes).extracting(TimeOffRequestService.Clash::name)
				.containsExactlyInAnyOrder("member", "colleague");
		// A person who decides nothing sees nothing.
		assertThat(requests.conflicts(member, monday(3), monday(3).plusDays(4))).isEmpty();
	}

	// --- fixtures --------------------------------------------------------------------

	/** The Monday [weeks] weeks from the Monday of this week. */
	private static LocalDate monday(int weeks) {
		return TODAY.plusWeeks(weeks);
	}

	/** A full working week, [weeks] weeks out. */
	private TimeOffRequestService.Draft week(int weeks) {
		return draft(monday(weeks), monday(weeks).plusDays(4));
	}

	private TimeOffRequestService.Draft draft(LocalDate from, LocalDate to) {
		return new TimeOffRequestService.Draft(vacation.getId(), from, to, null, null, null, null);
	}

	private int remaining() {
		return balances.balances(keeper, member.getId(), YEAR).stream()
				.filter(balance -> balance.typeId().equals(vacation.getId()))
				.findFirst().orElseThrow().remainingMilliDays();
	}

	private List<TimeOffLedgerEntry> bookingsOf(User person) {
		return ledger.findAll().stream()
				.filter(row -> row.getUserId().equals(person.getId()))
				.filter(row -> row.getKind() == TimeOffLedgerEntry.Kind.BOOKED
						|| row.getKind() == TimeOffLedgerEntry.Kind.RETURNED)
				.toList();
	}

	private List<Notification> bellOf(User person) {
		return mongo.find(Query.query(Criteria.where("userId").is(person.getId())), Notification.class);
	}

	/** The built-in vacation type, turned into one somebody has to approve. */
	private TimeOffType approvable(TimeOffType type) {
		type.setApprovalRequired(true);
		type.setApproverRule(TimeOffType.ApproverRule.ADMIN);
		type.setCountsAgainstBalance(true);
		type.setAllowanceMilliDays(20 * DAY);
		return typeRepository.save(type);
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").locale("en")
				.build());
	}

	private void enableModule(List<String> managers) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setAbsenceManagementEnabled(true);
		block.setAbsenceManagers(managers);
		current.setTimeTracking(block);
		settings.save(current);
	}
}
