package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.AccountAdmission;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.migration.AccountApprovalOnUpdate;
import com.ahmadre.hinata.migration.MigrationMarkers;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.setup.AdminSettingsController;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.setup.SetupService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who comes in without anybody deciding (HIN-175): an open registration and an SSO provider that
 * lets in whoever it knows are named; the update closes both and tells the administrators; opening
 * one again takes a confirmation; a new instance starts closed.
 */
class AccountAdmissionIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	AccountAdmission admission;
	@Autowired
	AccountApprovalOnUpdate migration;
	@Autowired
	MigrationMarkers markers;
	@Autowired
	SettingsService settings;
	@Autowired
	AdminSettingsController adminSettings;
	@Autowired
	SetupService setup;

	private User admin;

	@BeforeEach
	void openInstance() {
		for (String collection : List.of("server_settings", "audit_log")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		mongo.getCollection(MigrationMarkers.COLLECTION).deleteMany(new Document("_id", "account-approval-on-update"));
		admin = user("admin", Role.ADMIN);
		ServerSettings stored = new ServerSettings();
		stored.setSetupCompleted(true);
		stored.getApp().setLocalAuthEnabled(true);
		stored.getApp().setRegistrationEnabled(true);
		stored.getApp().setRequireAdminApproval(false);
		stored.getOidc().setEnabled(true);
		stored.getOidc().setDisplayName("Firmen-SSO");
		stored.getSaml().setEnabled(true);
		stored.getSaml().setAllowedDomains(List.of("example.org"));
		settings.save(stored);
	}

	private List<String> openWays() {
		return admission.openWays().stream().map(AccountAdmission.OpenWay::way).toList();
	}

	@Test
	void anOpenRegistrationAndAnOpenProviderAreNamed() {
		assertThat(admission.openWays()).extracting(AccountAdmission.OpenWay::way, AccountAdmission.OpenWay::name)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(AccountAdmission.REGISTRATION, null),
						org.assertj.core.groups.Tuple.tuple(AccountAdmission.OIDC, "Firmen-SSO"));
		// Named domains: only those come in straight away.
		assertThat(admission.firstSignInWaits(AccountAdmission.SAML, "kim@example.org")).isFalse();
		assertThat(admission.firstSignInWaits(AccountAdmission.SAML, "kim@elsewhere.example")).isTrue();
		assertThat(admission.firstSignInWaits(AccountAdmission.OIDC, "kim@elsewhere.example")).isFalse();
		assertThat(admission.firstSignInWaits("kerberos", "kim@example.org")).as("unknown waits").isTrue();
	}

	@Test
	void theUpdateClosesEveryOpenWayAndTellsTheAdministrators() {
		migration.run(null);

		ServerSettings stored = settings.get();
		assertThat(stored.getApp().getRequireAdminApproval()).isTrue();
		assertThat(stored.getOidc().getRequireApproval()).isTrue();
		assertThat(stored.getSaml().getRequireApproval()).as("domains named, left as it was").isNull();
		assertThat(openWays()).isEmpty();
		assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.ACCOUNT_APPROVAL_CHANGED)),
				AuditLog.class)).extracting(entry -> entry.getMetadata().get("way"))
				.containsExactlyInAnyOrder(AccountAdmission.REGISTRATION, AccountAdmission.OIDC);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(admin.getId())
				.and("type").is(Notification.Type.SYSTEM)), Notification.class)).isEqualTo(1);
		assertThat(markers.done("account-approval-on-update")).isTrue();
	}

	@Test
	void openingAWayAgainTakesAConfirmation() {
		migration.run(null);
		actingAs(admin);
		ServerSettings opening = settings.get();
		opening.getApp().setRequireAdminApproval(false);

		assertThatThrownBy(() -> adminSettings.update(opening))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getMessageKey()).isEqualTo("error.moderation.openAccessUnconfirmed");
					assertThat(ex.getDetails()).containsEntry("code", "error.moderation.openAccessUnconfirmed")
							.containsEntry("ways", AccountAdmission.REGISTRATION);
				});
		assertThat(openWays()).isEmpty();

		opening.setOpenAccessConfirmed(true);
		adminSettings.update(opening);

		assertThat(openWays()).containsExactly(AccountAdmission.REGISTRATION);
		assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.ACCOUNT_APPROVAL_CHANGED)
				.and("metadata.open").is("true")), AuditLog.class)).singleElement()
				.satisfies(entry -> assertThat(entry.getActorId()).isEqualTo(admin.getId()));
	}

	@Test
	void aNewInstanceStartsClosed() {
		mongo.getCollection("server_settings").deleteMany(new Document());

		setup.complete(new SetupService.SetupRequest("Werkhaus", "first@example.org", "first", "First", "Sicher!12345"));

		ServerSettings stored = settings.get();
		assertThat(stored.getApp().getRequireAdminApproval()).isTrue();
		assertThat(List.of(stored.getOidc().getRequireApproval(), stored.getOauth2().getRequireApproval(),
				stored.getSaml().getRequireApproval(), stored.getLdap().getRequireApproval())).containsOnly(true);
	}
}
