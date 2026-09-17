package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
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
 * Entitlements, balances and the journal against a real database: what a grant does, what a
 * correction does, who may look, and what survives an account deletion.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimeOffBalanceIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeOffBalanceIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	/** A fixed day in 2026, so "this year" and "in the future" mean the same thing every run. */
	static final Instant NOW = Instant.parse("2026-06-15T09:00:00Z");
	private static final int YEAR = 2026;

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
	private TimeOffBalanceService timeOff;
	@Autowired
	private TimeOffTypeService types;
	@Autowired
	private TimeOffLedgerRepository ledger;
	@Autowired
	private TimeOffEmploymentRepository employment;

	private User member;
	private User keeper;
	private User other;
	private TimeOffType vacation;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "time_off_types", "time_off_ledger",
				"time_off_entitlements", "time_off_employment", "notifications", "audit_log",
				"server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = user("member", Role.MEMBER);
		keeper = user("keeper", Role.MEMBER);
		other = user("other", Role.MEMBER);
		enableModule(List.of(keeper.getId()));
		vacation = types.byKey(TimeOffType.SYSTEM_VACATION).orElseThrow();
	}

	// --- granting ---------------------------------------------------------------

	@Test
	@DisplayName("a grant writes the entitlement and the accrual behind it")
	void grantingAYearAccruesIt() {
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);

		TimeOffBalanceService.Balance balance = balanceOf(member, vacation);
		assertThat(balance.entitledMilliDays()).isEqualTo(20 * TimeOffType.DAY);
		assertThat(balance.accruedMilliDays()).isEqualTo(20 * TimeOffType.DAY);
		assertThat(balance.remainingMilliDays()).isEqualTo(20 * TimeOffType.DAY);
		assertThat(balance.granted()).isTrue();

		// One accrual, dated the first day of the leave year, pointing at the grant.
		List<TimeOffLedgerEntry> rows = ledger.findAll();
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().getKind()).isEqualTo(TimeOffLedgerEntry.Kind.ACCRUAL);
		assertThat(rows.getFirst().getEffectiveOn()).isEqualTo(LocalDate.of(YEAR, 1, 1));
	}

	@Test
	void grantingTheSameYearTwiceIsRefused() {
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);

		assertThatThrownBy(() -> timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.alreadyGranted");
	}

	@Test
	@DisplayName("the joining date decides what the year is worth")
	void aLateJoinerAccruesTwelfths() {
		timeOff.saveEmployment(keeper, member.getId(), LocalDate.of(YEAR, 7, 15), null, null);

		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);

		// August to December: five twelfths of twenty days, and 8.33 stays 8.33 (§ 5 BUrlG).
		assertThat(balanceOf(member, vacation).remainingMilliDays()).isEqualTo(8_333);
	}

	@Test
	void theGrantedPersonIsToldAndTheChangeIsAudited() {
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);

		List<Notification> bell = mongo.find(
				Query.query(Criteria.where("userId").is(member.getId())), Notification.class);
		assertThat(bell).hasSize(1);
		assertThat(bell.getFirst().getType())
				.isEqualTo(Notification.Type.TIME_OFF_ENTITLEMENT_CHANGED);
		// The keeper who granted it is not notified of their own action.
		assertThat(mongo.find(Query.query(Criteria.where("userId").is(keeper.getId())),
				Notification.class)).isEmpty();

		List<AuditLog> audit = mongo.find(Query.query(
				Criteria.where("action").is(AuditAction.TIME_OFF_ENTITLEMENT_CHANGED.name())),
				AuditLog.class);
		assertThat(audit).hasSize(1);
		assertThat(audit.getFirst().getMetadata()).containsEntry("year", String.valueOf(YEAR));
	}

	@Test
	void aBulkGrantPreviewsFirstAndSkipsWhoeverAlreadyHasOne() {
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);

		List<TimeOffBalanceService.GrantPreview> preview = timeOff.previewGrant(keeper, vacation.getId(),
				YEAR, List.of(member.getId(), other.getId()), null);
		assertThat(preview).extracting(TimeOffBalanceService.GrantPreview::alreadyGranted)
				.containsExactly(true, false);
		// A preview is a preview: nothing was written.
		assertThat(ledger.count()).isEqualTo(1);

		List<TimeOffEntitlement> granted = timeOff.grantMany(keeper, vacation.getId(), YEAR,
				List.of(member.getId(), other.getId()), null);
		assertThat(granted).hasSize(1);
		assertThat(granted.getFirst().getUserId()).isEqualTo(other.getId());
	}

	// --- corrections ---------------------------------------------------------------

	@Test
	@DisplayName("a correction is a booking with a reason, never an edit")
	void anAdjustmentMovesTheBalanceAndKeepsTheAccrual() {
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);

		timeOff.adjust(keeper, member.getId(), vacation.getId(), YEAR, 5 * TimeOffType.DAY, null,
				"Additional leave, § 208 SGB IX");

		assertThat(balanceOf(member, vacation).remainingMilliDays()).isEqualTo(25 * TimeOffType.DAY);
		// Both rows are still there; nothing was rewritten.
		assertThat(ledger.count()).isEqualTo(2);
		assertThat(balanceOf(member, vacation).accruedMilliDays()).isEqualTo(20 * TimeOffType.DAY);
		assertThat(balanceOf(member, vacation).adjustedMilliDays()).isEqualTo(5 * TimeOffType.DAY);
	}

	@Test
	void aCorrectionWithoutAReasonIsRefused() {
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);

		assertThatThrownBy(() -> timeOff.adjust(keeper, member.getId(), vacation.getId(), YEAR,
				TimeOffType.DAY, null, "  "))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.reasonRequired");
	}

	@Test
	@DisplayName("two keepers booking at once lose nothing")
	void concurrentBookingsAllCount() throws Exception {
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);
		int writers = 8;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(writers);
		AtomicInteger failures = new AtomicInteger();

		for (int i = 0; i < writers; i++) {
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					timeOff.adjust(keeper, member.getId(), vacation.getId(), YEAR, TimeOffType.DAY,
							null, "parallel correction");
				}
				catch (Exception failed) {
					failures.incrementAndGet();
				}
				finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(failures).hasValue(0);
		// The point of an append-only journal: no writer read a total to write back, so none of the
		// eight days went missing (reference_derived_counter_lost_update).
		assertThat(balanceOf(member, vacation).remainingMilliDays())
				.isEqualTo((20 + writers) * TimeOffType.DAY);
	}

	// --- who may look -----------------------------------------------------------------

	@Test
	void everybodySeesTheirOwnBalanceAndNobodyElsesUnlessTheyKeepThem() {
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);

		assertThat(timeOff.balances(member, null, YEAR)).isNotEmpty();
		assertThatThrownBy(() -> timeOff.balances(other, member.getId(), YEAR))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.forbidden");
		assertThat(timeOff.balances(keeper, member.getId(), YEAR)).isNotEmpty();

		// Keeping is a keeper's too: a member cannot grant, correct or set employment dates.
		assertThatThrownBy(() -> timeOff.adjust(member, member.getId(), vacation.getId(), YEAR,
				TimeOffType.DAY, null, "please")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> timeOff.saveEmployment(member, member.getId(),
				LocalDate.of(2020, 1, 1), null, null)).isInstanceOf(ApiException.class);
	}

	// --- employment dates ---------------------------------------------------------------

	@Test
	void leavingBeforeJoiningIsRefused() {
		assertThatThrownBy(() -> timeOff.saveEmployment(keeper, member.getId(),
				LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), null))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.employmentOrder");
	}

	@Test
	@DisplayName("deleting an account takes the employment dates and leaves the record")
	void erasureRemovesTheDatesAndKeepsTheJournal() {
		timeOff.saveEmployment(keeper, member.getId(), LocalDate.of(2020, 1, 1), null, null);
		timeOff.grant(keeper, member.getId(), vacation.getId(), YEAR, null, null);
		String userId = member.getId();

		userService.delete(member);

		assertThat(employment.findByUserId(userId)).isEmpty();
		// What was granted and taken is the record; it keeps the id as a pseudonym, like a work item.
		assertThat(mongo.find(Query.query(Criteria.where("userId").is(userId)),
				TimeOffLedgerEntry.class)).hasSize(1);
		assertThat(mongo.find(Query.query(Criteria.where("userId").is(userId)),
				TimeOffEntitlement.class)).hasSize(1);
	}

	// --- the statutory floor ---------------------------------------------------------------

	@Test
	void anAllowanceBelowFourWeeksIsFlaggedAndStillSaved() {
		TimeOffType thin = types.update(keeper, vacation.getId(),
				TimeOffTypeService.Draft.builder().allowanceMilliDays(18 * TimeOffType.DAY).build());

		TimeOffBalanceService.LegalFloor floor = timeOff.legalFloor(member.getId(), thin);

		// Nobody saved a working pattern, so the instance default — five days — decides.
		assertThat(floor.workingDaysPerWeek()).isEqualTo(5);
		assertThat(floor.minimumMilliDays()).isEqualTo(20 * TimeOffType.DAY);
		assertThat(floor.fallsShort()).isTrue();
		// Saved all the same: a warning, never a refusal.
		assertThat(thin.allowanceMilliDays()).isEqualTo(18 * TimeOffType.DAY);
	}

	// --- helpers -------------------------------------------------------------------------

	private TimeOffBalanceService.Balance balanceOf(User person, TimeOffType type) {
		return timeOff.balances(keeper, person.getId(), YEAR).stream()
				.filter(balance -> balance.typeId().equals(type.getId()))
				.findFirst().orElseThrow();
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
