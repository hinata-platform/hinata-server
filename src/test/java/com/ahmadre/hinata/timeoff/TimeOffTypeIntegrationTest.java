package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The catalogue of absence types against a real database: what an instance starts with, who may
 * keep it, and the rules it refuses to break.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class TimeOffTypeIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private UserRepository users;
	@Autowired
	private TimeOffTypeService types;
	@Autowired
	private TimeOffTypeRepository typeRepository;
	@Autowired
	private TimeOffLedgerRepository ledger;
	@Autowired
	private TimeOffSystemTypes systemTypes;
	private User member;
	private User keeper;
	private User admin;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "time_off_types", "time_off_ledger",
				"time_off_entitlements", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = user("member", Role.MEMBER);
		keeper = user("keeper", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
		enableModule(List.of(keeper.getId()));
	}

	// --- what an instance starts with ------------------------------------------

	@Test
	void switchingTheModuleOnCreatesTheThreeSystemTypes() {
		List<TimeOffType> catalogue = types.list(member, false);

		assertThat(catalogue).extracting(TimeOffType::getKey)
				.containsExactlyInAnyOrder(TimeOffType.SYSTEM_VACATION, TimeOffType.SYSTEM_SICK,
						TimeOffType.SYSTEM_OTHER);
		// No name of their own: the client renders the translated label for the system key, so a
		// German instance does not read "Vacation" until somebody renames three rows.
		assertThat(catalogue).allSatisfy(type -> assertThat(type.getName()).isNull());
		assertThat(catalogue).allSatisfy(type -> assertThat(type.isSystem()).isTrue());

		// The statutory floor on a five-day week (§ 3 Abs. 1 BUrlG), not a generous guess.
		TimeOffType vacation = types.byKey(TimeOffType.SYSTEM_VACATION).orElseThrow();
		assertThat(vacation.allowanceMilliDays())
				.isEqualTo(TimeOffSystemTypes.DEFAULT_VACATION_MILLI_DAYS);
		assertThat(vacation.requiresApproval()).isTrue();
		assertThat(vacation.waitingPeriodMonths()).isEqualTo(6);
		assertThat(vacation.carryoverExpiresOn().getMonthValue()).isEqualTo(3);
		assertThat(vacation.carryoverExpiresOn().getDayOfMonth()).isEqualTo(31);
	}

	@Test
	void creatingTheSystemTypesTwiceChangesNothing() {
		systemTypes.ensure();
		systemTypes.ensure();

		assertThat(typeRepository.count()).isEqualTo(3);
	}

	@Test
	void aSickTypeNeverRequiresApprovalHoweverTheDocumentWasWritten() {
		// Written past the service, the way an older version or a hand edit would.
		mongo.updateFirst(Query.query(Criteria.where("key").is(TimeOffType.SYSTEM_SICK)),
				new org.springframework.data.mongodb.core.query.Update().set("approvalRequired", true),
				TimeOffType.class);

		TimeOffType sick = types.byKey(TimeOffType.SYSTEM_SICK).orElseThrow();

		assertThat(sick.getApprovalRequired()).isTrue();
		// § 5 EFZG: sickness is notified, not applied for. The stored flag does not get a vote.
		assertThat(sick.requiresApproval()).isFalse();
	}

	// --- who may keep it ---------------------------------------------------------

	@Test
	void everybodyReadsTheCatalogueAndOnlyKeepersChangeIt() {
		assertThat(types.list(member, false)).hasSize(3);

		assertThatThrownBy(() -> types.create(member, draft("training")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.forbidden");

		// A named keeper is not an administrator and still keeps the catalogue.
		assertThat(keeper.isAdmin()).isFalse();
		assertThat(types.create(keeper, draft("training")).getKey()).isEqualTo("training");
		assertThat(types.create(admin, draft("sabbatical")).getKey()).isEqualTo("sabbatical");
	}

	@Test
	void removingTheLastNamedKeeperLeavesAdministratorsOnly() {
		enableModule(List.of());

		assertThatThrownBy(() -> types.create(keeper, draft("training")))
				.isInstanceOf(ApiException.class);
		assertThat(types.create(admin, draft("training")).getKey()).isEqualTo("training");
	}

	// --- the rules ----------------------------------------------------------------

	@Test
	void aSickTypeCannotBeMadeSubjectToApproval() {
		assertThatThrownBy(() -> types.create(admin, draft("illness").toBuilder()
				.kind(TimeOffType.Kind.SICK).approvalRequired(true).build()))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.sickNeedsNoApproval");
	}

	@Test
	void anUnlimitedTypeCannotAlsoCarryABalance() {
		assertThatThrownBy(() -> types.create(admin, draft("unpaid").toBuilder()
				.unlimited(true).allowanceMilliDays(5 * TimeOffType.DAY).build()))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.unlimitedHasNoBalance");
	}

	@Test
	void aKeyIsASlugAndIsTakenOnlyOnce() {
		assertThatThrownBy(() -> types.create(admin, draft("Not a key!")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.typeKeyInvalid");

		types.create(admin, draft("training"));
		assertThatThrownBy(() -> types.create(admin, draft("TRAINING")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.typeKeyTaken");
	}

	@Test
	void anImpossibleDayOfTheYearIsRefused() {
		assertThatThrownBy(() -> types.create(admin, draft("training").toBuilder()
				.carryoverExpiresMonth(2).carryoverExpiresDay(31).build()))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.monthDayInvalid");
	}

	// --- deleting versus switching off ---------------------------------------------

	@Test
	void aSystemTypeIsSwitchedOffRatherThanDeleted() {
		TimeOffType sick = types.byKey(TimeOffType.SYSTEM_SICK).orElseThrow();

		assertThatThrownBy(() -> types.delete(admin, sick.getId()))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.typeSystemUndeletable");

		types.update(admin, sick.getId(), TimeOffTypeService.Draft.builder().active(false).build());
		assertThat(types.list(member, false)).extracting(TimeOffType::getKey)
				.doesNotContain(TimeOffType.SYSTEM_SICK);
		assertThat(types.list(admin, true)).extracting(TimeOffType::getKey)
				.contains(TimeOffType.SYSTEM_SICK);
		// A member asking for everything still only gets what is on offer.
		assertThat(types.list(member, true)).extracting(TimeOffType::getKey)
				.doesNotContain(TimeOffType.SYSTEM_SICK);
	}

	@Test
	void aTypeWithHistoryIsSwitchedOffRatherThanDeleted() {
		TimeOffType training = types.create(admin, draft("training"));
		assertThat(types.list(admin, true)).hasSize(4);

		// An unused type goes.
		TimeOffType spare = types.create(admin, draft("spare"));
		types.delete(admin, spare.getId());
		assertThat(typeRepository.findById(spare.getId())).isEmpty();

		ledger.save(TimeOffLedgerEntry.builder().userId(member.getId()).typeId(training.getId())
				.year(2026).kind(TimeOffLedgerEntry.Kind.ACCRUAL).milliDays(5 * TimeOffType.DAY)
				.effectiveOn(LocalDate.of(2026, 1, 1)).build());

		assertThatThrownBy(() -> types.delete(admin, training.getId()))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.typeInUse");
	}

	@Test
	void keepingTheCatalogueIsAudited() {
		types.create(admin, draft("training"));

		List<AuditLog> events = mongo.find(
				Query.query(Criteria.where("action").is(AuditAction.TIME_OFF_TYPE_CHANGED.name())),
				AuditLog.class);

		assertThat(events).hasSize(1);
		assertThat(events.getFirst().getMetadata())
				.containsEntry("change", "created")
				.containsEntry("key", "training");
	}

	// --- helpers --------------------------------------------------------------------

	private TimeOffTypeService.Draft draft(String key) {
		return TimeOffTypeService.Draft.builder()
				.key(key)
				.name(key)
				.kind(TimeOffType.Kind.SPECIAL)
				.build();
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
		// save() publishes the event the system types hang off, so the catalogue arrives with the
		// switch rather than on the first read.
		settings.save(current);
	}
}
