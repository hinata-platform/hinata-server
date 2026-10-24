package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.storage.TestImages;
import com.ahmadre.hinata.storage.UploadCandidate;
import com.ahmadre.hinata.storage.UploadGate;
import com.ahmadre.hinata.storage.UploadGroup;
import com.ahmadre.hinata.storage.UploadKind;
import com.ahmadre.hinata.storage.UploadRefusal;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The file types an instance switches off (HIN-177): stored with the moderation settings, read by
 * the upload gate on the next upload, and kept by a save that does not send them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class UploadSwitchesIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private AdminSettingsController adminSettings;
	@Autowired
	private UserRepository users;
	@Autowired
	private UploadGate gate;

	@MockitoBean
	private CurrentUser currentUser;

	@BeforeEach
	void clean() {
		mongo.getCollection("server_settings").deleteMany(new Document());
		settings.save(new ServerSettings());
	}

	private static UploadCandidate pdf() {
		return UploadCandidate.of(UploadKind.ATTACHMENT, TestImages.pdf(), "application/pdf", "a.pdf");
	}

	@Test
	@DisplayName("a group switched off is refused on the next upload and kept by a save from an older app")
	void aSwitchedOffGroupIsRefusedAndKept() {
		assertThat(gate.refusalOf(pdf())).isEmpty();

		ServerSettings current = settings.get();
		ServerSettings.Moderation block = new ServerSettings.Moderation();
		ServerSettings.Uploads uploads = new ServerSettings.Uploads();
		uploads.setDisabledGroups(Set.of(UploadGroup.PDF));
		block.setUploads(uploads);
		current.setModeration(block);
		settings.save(current);

		assertThat(gate.refusalOf(pdf())).contains(UploadRefusal.DISABLED);

		User admin = users.save(User.builder().username("admin").email("admin@example.org").displayName("Admin")
				.roles(Set.of(Role.ADMIN, Role.MEMBER)).build());
		when(currentUser.require()).thenReturn(admin);
		ServerSettings fromAnOlderApp = settings.get();
		fromAnOlderApp.getModeration().setUploads(null);
		fromAnOlderApp.getModeration().setOperatorName("Beispiel GmbH");
		adminSettings.update(fromAnOlderApp);

		assertThat(settings.get().getModeration().getOperatorName()).isEqualTo("Beispiel GmbH");
		assertThat(settings.get().getModeration().getUploads().getDisabledGroups()).containsExactly(UploadGroup.PDF);
		assertThat(gate.refusalOf(pdf())).contains(UploadRefusal.DISABLED);
	}
}
