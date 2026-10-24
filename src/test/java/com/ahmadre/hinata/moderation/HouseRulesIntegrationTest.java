package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.auth.AuthController;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.moderation.rules.HouseRulesService;
import com.ahmadre.hinata.moderation.rules.RulesAcceptance;
import com.ahmadre.hinata.moderation.rules.RulesVersion;
import com.ahmadre.hinata.oauth.OAuthConsentController;
import com.ahmadre.hinata.pat.PatController;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Versions of the house rules and who accepted which (HIN-175): every change a reader would see
 * makes a version that never changes again, the first and those an administrator asks for make
 * everybody accept anew, and until then no new token writes and no OAuth client gets consent.
 */
class HouseRulesIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	HouseRulesService rules;
	@Autowired
	SettingsService settings;
	@Autowired
	AuthController auth;
	@Autowired
	PatController pats;
	@Autowired
	OAuthConsentController consent;
	@Autowired
	ModerationPersonalData personalData;
	@Autowired
	ApplicationEventPublisher events;

	@BeforeEach
	void freshRules() {
		for (String collection : List.of("moderation_rules_versions", "rules_acceptances", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		// The first save after the rules are gone writes the first version, as the first start does.
		settings.save(new ServerSettings());
	}

	private ServerSettings withContact(String email) {
		ServerSettings stored = settings.get();
		ServerSettings.Moderation block = stored.getModeration() == null ? new ServerSettings.Moderation()
				: stored.getModeration();
		block.setContactEmail(email);
		stored.setModeration(block);
		return stored;
	}

	private User reload(User user) {
		return users.findById(user.getId()).orElseThrow();
	}

	@Test
	void theFirstVersionAsksEverybodyAndASaveThatChangesNothingMakesNone() {
		RulesVersion first = rules.latest();
		assertThat(first.getVersion()).isEqualTo(1);
		assertThat(first.getRequiresReacceptance()).isTrue();
		assertThat(first.getRendered()).hasSize(9);
		assertThat(rules.pending(reader)).isTrue();

		settings.save(settings.get());

		assertThat(rules.latest().getVersion()).isEqualTo(1);
	}

	@Test
	void aNewContactMakesAVersionThatAsksNobodyAgain() {
		rules.accept(reader, 1);
		settings.save(withContact("moderation@example.org"));

		RulesVersion second = rules.latest();
		assertThat(second.getVersion()).isEqualTo(2);
		assertThat(second.getRequiresReacceptance()).isFalse();
		assertThat(second.renderedIn("de")).contains("moderation@example.org");
		assertThat(rules.pending(reload(reader))).as("accepted the first").isFalse();
		assertThat(rules.pending(author)).as("accepted none").isTrue();
		// Version 1 still says what it said.
		assertThat(rules.version(1).renderedIn("de")).doesNotContain("moderation@example.org");
	}

	@Test
	void onlyTheCurrentVersionCanBeAcceptedAndOnceIsEnough() {
		settings.save(withContact("moderation@example.org"));

		assertThatThrownBy(() -> rules.accept(reader, 1))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(ex.getMessageKey()).isEqualTo("error.rules.notCurrent");
				});
		rules.accept(reader, 2);
		rules.accept(reader, 2);

		assertThat(reload(reader).getRulesAcceptedVersion()).isEqualTo(2);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(reader.getId())), RulesAcceptance.class))
				.isEqualTo(1);
		assertThat(rules.pending(reload(reader))).isFalse();
	}

	@Test
	void askingEverybodyAgainMakesEverybodyAcceptAgain() {
		rules.accept(reader, 1);
		User admin = user("admin", Role.ADMIN);

		RulesVersion again = rules.requireReacceptance(admin);

		assertThat(again.getVersion()).isEqualTo(2);
		assertThat(again.getRequiresReacceptance()).isTrue();
		assertThat(again.getCreatedBy()).isEqualTo(admin.getId());
		assertThat(rules.pending(reload(reader))).isTrue();
	}

	@Test
	void ownRulesAndRulesElsewhereKeepHowModerationWorks() {
		ServerSettings stored = settings.get();
		ServerSettings.Moderation block = new ServerSettings.Moderation();
		ServerSettings.Rules own = new ServerSettings.Rules();
		own.setMode(ServerSettings.RulesMode.TEXT);
		own.setText("# Unsere Regeln\n\nSeid freundlich.");
		block.setRules(own);
		stored.setModeration(block);
		settings.save(stored);

		assertThat(rules.latest().renderedIn("de")).startsWith("# Unsere Regeln").contains("## So moderieren wir",
				"Gemeldete Bilder prüft ein Mensch.");

		own.setMode(ServerSettings.RulesMode.URL);
		own.setUrl("https://example.org/regeln");
		settings.save(stored);

		assertThat(rules.latest().getMode()).isEqualTo(ServerSettings.RulesMode.URL);
		assertThat(rules.latest().renderedIn("en")).contains("https://example.org/regeln", "## How we moderate");
	}

	@Test
	void theSignedInPersonSeesWhetherTheRulesWait() {
		actingAs(reader);
		assertThat(auth.me().rulesPending()).isTrue();
		assertThat(auth.me().rulesVersion()).isEqualTo(1);

		rules.accept(reader, 1);
		// Every request loads the person afresh.
		actingAs(reload(reader));

		assertThat(auth.me().rulesPending()).isFalse();
	}

	/** A token writes for its owner as long as it lives, and an OAuth client the same. */
	@Test
	void noNewTokenAndNoConsentBeforeTheRules() {
		actingAs(reader);

		assertThatThrownBy(() -> pats.create(new PatController.CreatePatRequest("Agent", Set.of("issues:read"), 30L)))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.rules.acceptRequired"));
		assertThatThrownBy(() -> consent.decide(new OAuthConsentController.ConsentDecision("request", true,
				List.of("issues:read"))))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.rules.acceptRequired"));

		rules.accept(reader, 1);
		actingAs(reload(reader));

		assertThat(pats.create(new PatController.CreatePatRequest("Agent", Set.of("issues:read"), 30L)).token())
				.isNotBlank();
	}

	@Test
	@SuppressWarnings("unchecked")
	void acceptancesAreInTheExportAndGoWithTheAccount() {
		rules.accept(reader, 1);

		Map<String, Object> exported = (Map<String, Object>) personalData.data(reload(reader));
		assertThat((List<Map<String, Object>>) exported.get("rulesAccepted")).singleElement()
				.satisfies(row -> assertThat(row.get("version")).isEqualTo(1));

		events.publishEvent(new UserService.UserDeletedEvent(reader.getId()));

		assertThat(mongo.count(Query.query(Criteria.where("userId").is(reader.getId())), RulesAcceptance.class))
				.isZero();
	}
}
