package com.ahmadre.hinata.meta;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.setup.AdminSettingsController;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * What everybody learns about moderation before signing in (HIN-26, HIN-167): that reporting
 * and blocking exist, and whom the operator names for reports. Over HTTP without a token,
 * the way the app asks on every start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ModerationMetaIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final ObjectMapper JSON = new ObjectMapper();

	@LocalServerPort
	private int port;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private AdminSettingsController adminSettings;
	@Autowired
	private UserRepository users;
	@Autowired
	private Validator validator;

	@MockitoBean
	private CurrentUser currentUser;

	@BeforeEach
	void clean() {
		mongo.getCollection("server_settings").deleteMany(new Document());
		settings.save(new ServerSettings());
	}

	@Test
	@DisplayName("without a contact, the meta says reporting exists and that the contact is missing")
	void aMissingContactIsPublishedAsMissing() throws Exception {
		JsonNode moderation = meta().path("moderation");

		assertThat(moderation.path("version").asInt()).isEqualTo(1);
		assertThat(moderation.path("contactMissing").asBoolean()).isTrue();
		assertThat(moderation.path("contactEmail").isNull()).isTrue();
	}

	@Test
	@DisplayName("a stored contact is published to everybody, and a save that leaves the block out keeps it")
	void theStoredContactIsPublished() throws Exception {
		ServerSettings current = settings.get();
		ServerSettings.Moderation block = new ServerSettings.Moderation();
		block.setOperatorName("Beispiel GmbH");
		block.setContactEmail("meldungen@example.org");
		block.setImprintUrl("https://example.org/impressum");
		current.setModeration(block);
		settings.save(current);

		JsonNode moderation = meta().path("moderation");
		assertThat(moderation.path("operatorName").asText()).isEqualTo("Beispiel GmbH");
		assertThat(moderation.path("contactEmail").asText()).isEqualTo("meldungen@example.org");
		assertThat(moderation.path("imprintUrl").asText()).isEqualTo("https://example.org/impressum");
		assertThat(moderation.path("contactMissing").asBoolean()).isFalse();

		User admin = users.save(User.builder().username("admin").email("admin@example.org").displayName("Admin")
				.roles(Set.of(Role.ADMIN, Role.MEMBER)).build());
		when(currentUser.require()).thenReturn(admin);
		ServerSettings withoutBlock = settings.get();
		withoutBlock.setModeration(null);
		adminSettings.update(withoutBlock);
		assertThat(settings.get().getModeration().getContactEmail()).isEqualTo("meldungen@example.org");
	}

	@Test
	@DisplayName("the imprint must be an https address and the contact a mail address")
	void theContactIsValidated() {
		ServerSettings invalid = new ServerSettings();
		ServerSettings.Moderation block = new ServerSettings.Moderation();
		block.setContactEmail("not an address");
		block.setImprintUrl("http://example.org/impressum");
		invalid.setModeration(block);

		assertThat(validator.validate(invalid))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("moderation.contactEmail", "moderation.imprintUrl");
	}

	private JsonNode meta() throws Exception {
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/api/v1/meta")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return JSON.readTree(response.body());
	}
}
