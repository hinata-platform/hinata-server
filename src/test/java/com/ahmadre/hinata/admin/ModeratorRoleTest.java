package com.ahmadre.hinata.admin;

import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.me.MeService;
import com.ahmadre.hinata.migration.MigrationMarkers;
import com.ahmadre.hinata.migration.ModeratorRoleBackfill;
import com.ahmadre.hinata.moderation.ModerationAccess;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.people.PersonProfileService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The moderation role (HIN-26, HIN-167): handed out by administrators like the organisation
 * admin role and by the same rules, because both are kept apart from running the platform.
 * Against a real MongoDB, because "the last active holder" is a question of what the accounts
 * hold right now.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ModeratorRoleTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private SettingsService settings;
	@Autowired
	private AdminUserService adminUsers;
	@Autowired
	private MeService me;
	@Autowired
	private ModeratorRoleBackfill backfill;
	@Autowired
	private ModerationAccess moderation;
	@Autowired
	private PersonProfileService profiles;

	@MockitoBean
	private CurrentUser currentUser;

	private User admin;
	private User moderator;
	private User member;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "server_settings", "audit_log", "notifications",
				MigrationMarkers.COLLECTION)) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		settings.save(new ServerSettings());
		admin = user("admin", Role.ADMIN);
		moderator = user("moderator", Role.MODERATOR);
		member = user("member");
		actingAs(admin);
	}

	@Test
	@DisplayName("an administrator makes another person a moderator, on record, and both sides hear of it")
	void anAdministratorHandsOutTheRole() {
		adminUsers.setPlatformRole(List.of(member.getId()), Role.MODERATOR, true);

		assertThat(reload(member).isModerator()).isTrue();
		assertThat(mongo.find(Query.query(Criteria.where("action").is("USER_ROLE_CHANGED")
				.and("targetId").is(member.getId())), AuditLog.class))
				.singleElement()
				.satisfies(entry -> assertThat(entry.getMetadata())
						.containsEntry("role", "MODERATOR").containsEntry("to", "true"));
		assertThat(roleNotices(member)).hasSize(1);
		// The moderators already there learn who joins them.
		assertThat(roleNotices(moderator)).singleElement()
				.satisfies(notice -> assertThat(notice.getBody()).contains("member"));
		assertThat(adminUsers.getOne(member.getId()).moderator()).isTrue();
	}

	@Test
	@DisplayName("nobody makes themselves a moderator, and a selection with them changes nobody")
	void nobodyHandsTheRoleToThemselves() {
		assertThatThrownBy(() -> adminUsers.setPlatformRole(List.of(admin.getId()), Role.MODERATOR, true))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotGrantOwnModeratorRole");
		assertThatThrownBy(() -> adminUsers.setPlatformRole(List.of(member.getId(), admin.getId()),
				Role.MODERATOR, true))
				.isInstanceOf(ApiException.class);

		assertThat(reload(member).isModerator()).isFalse();
		assertThat(reload(admin).isModerator()).isFalse();
	}

	@Test
	@DisplayName("the last active moderator keeps the role, the account and the access")
	void theLastModeratorStays() {
		assertThatThrownBy(() -> adminUsers.setPlatformRole(List.of(moderator.getId()), Role.MODERATOR, false))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotRemoveLastModerator");
		assertThatThrownBy(() -> adminUsers.setStatus(List.of(moderator.getId()), "DISABLED"))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotRemoveLastModerator");
		assertThatThrownBy(() -> adminUsers.delete(List.of(moderator.getId())))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotRemoveLastModerator");
		assertThatThrownBy(() -> me.deleteAccount(reload(moderator)))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotRemoveLastModerator");
		assertThat(reload(moderator).isModerator()).isTrue();
		assertThat(reload(moderator).isActive()).isTrue();

		// With a second one, the first may go.
		adminUsers.setPlatformRole(List.of(member.getId()), Role.MODERATOR, true);
		adminUsers.setPlatformRole(List.of(moderator.getId()), Role.MODERATOR, false);
		assertThat(reload(moderator).isModerator()).isFalse();
	}

	@Test
	@DisplayName("the organisation admin role keeps its rules on the shared path")
	void theOrganisationRoleFollowsTheSameRules() {
		User orgAdmin = user("orgadmin", Role.ORG_ADMIN);

		assertThatThrownBy(() -> adminUsers.setPlatformRole(List.of(admin.getId()), Role.ORG_ADMIN, true))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotGrantOwnOrgRole");
		assertThatThrownBy(() -> adminUsers.setStatus(List.of(orgAdmin.getId()), "DISABLED"))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotRemoveLastOrgAdmin");
		adminUsers.setPlatformRole(List.of(member.getId()), Role.ORG_ADMIN, true);
		assertThat(reload(member).isOrgAdmin()).isTrue();
		assertThat(reload(member).isModerator()).isFalse();
	}

	@Test
	@DisplayName("the update makes every administrator a moderator once, on record, with a word on handing it on")
	void theHandOverMakesEveryAdministratorAModeratorOnce() {
		backfill.run(new DefaultApplicationArguments());

		assertThat(reload(admin).isModerator()).isTrue();
		assertThat(reload(member).isModerator()).isFalse();
		assertThat(mongo.find(Query.query(Criteria.where("action").is("USER_ROLE_CHANGED")
				.and("targetId").is(admin.getId())), AuditLog.class))
				.singleElement()
				.satisfies(entry -> assertThat(entry.getMetadata())
						.containsEntry("role", "MODERATOR").containsEntry("reason", "migration"));
		assertThat(roleNotices(admin)).singleElement()
				.satisfies(notice -> assertThat(notice.getBody()).isNotBlank());

		// Taken away on purpose afterwards, it stays taken away.
		User separated = reload(admin);
		separated.setRoles(new HashSet<>(Set.of(Role.ADMIN, Role.MEMBER)));
		users.save(separated);
		backfill.run(new DefaultApplicationArguments());
		assertThat(reload(admin).isModerator()).isFalse();
	}

	@Test
	@DisplayName("moderating needs the role on the stored account: one taken away is gone at once")
	void moderatingNeedsTheRoleOnTheStoredAccount() {
		actingAs(moderator);
		assertThat(moderation.require().getId()).isEqualTo(moderator.getId());
		assertThat(moderation.moderators()).extracting(User::getId).containsExactly(moderator.getId());

		actingAs(member);
		assertThatThrownBy(() -> moderation.require())
				.isInstanceOf(ApiException.class).hasMessageContaining("moderatorOnly");

		// An administrator is no moderator by being one.
		actingAs(admin);
		assertThatThrownBy(() -> moderation.require()).isInstanceOf(ApiException.class);

		User demoted = reload(moderator);
		demoted.setRoles(new HashSet<>(Set.of(Role.MEMBER)));
		users.save(demoted);
		actingAs(moderator);
		assertThatThrownBy(() -> moderation.require()).isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("the user list filters for moderators and the profile names the role")
	void theListAndTheProfileKnowTheRole() {
		assertThat(adminUsers.list(null, "MODERATOR", null, null, "name", "asc", 1, 25).items())
				.extracting(AdminUserResponse::id).containsExactly(moderator.getId());
		assertThat(profiles.profile(member, moderator.getId()).roles()).containsExactly("MODERATOR", "MEMBER");
	}

	private void actingAs(User user) {
		when(currentUser.requireId()).thenReturn(user.getId());
		when(currentUser.require()).thenAnswer(invocation -> reload(user));
	}

	private List<Notification> roleNotices(User user) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId())
				.and("type").is(Notification.Type.ACCOUNT_ROLE_CHANGED)), Notification.class);
	}

	private User reload(User user) {
		return users.findById(user.getId()).orElseThrow();
	}

	private User user(String name, Role... extra) {
		Set<Role> roles = new HashSet<>(Set.of(Role.MEMBER));
		roles.addAll(List.of(extra));
		return users.save(User.builder().username(name).email(name + "@example.org")
				.displayName(name).roles(roles).build());
	}
}
