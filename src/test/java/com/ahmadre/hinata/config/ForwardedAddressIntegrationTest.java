package com.ahmadre.hinata.config;

import com.ahmadre.hinata.common.TestMongo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Through the real server: a client that writes a new {@code X-Forwarded-For} on every request
 * still counts as one address, so the limit on sign-in holds (HIN-175). Nothing is trusted as a
 * proxy here, which is the default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.auth-per-minute=3",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ForwardedAddressIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Test
	void aNewForwardedAddressOnEveryRequestDoesNotEscapeTheLimit() throws Exception {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 1; i <= 4; i++) {
			HttpResponse<Void> response = HTTP.send(HttpRequest.newBuilder(
					URI.create("http://localhost:" + port + "/api/v1/auth/me"))
					.header("X-Forwarded-For", "198.51.100." + i).GET().build(),
					HttpResponse.BodyHandlers.discarding());
			statuses.add(response.statusCode());
		}

		assertThat(statuses.subList(0, 3)).doesNotContain(429);
		assertThat(statuses.get(3)).isEqualTo(429);
	}
}
