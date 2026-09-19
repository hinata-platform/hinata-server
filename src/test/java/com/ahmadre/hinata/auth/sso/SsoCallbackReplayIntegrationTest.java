package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.common.TestMongo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An identity provider's callback that arrives twice gets one answer.
 *
 * <p>On iOS the second arrival's error reached the app before the first one's success, and the
 * person was told sign-in failed while it was in fact going through. Against a real MongoDB,
 * because the whole question is which of two writers gets a document.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SsoCallbackReplayIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final OAuth2AuthenticationException NOT_FOUND =
			new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private MongoAuthorizationRequestRepository requests;
	@Autowired
	private SsoCallbackReplay replay;

	@BeforeEach
	void clean() {
		mongo.dropCollection(PendingAuthorizationRequest.class);
	}

	private void pending(String state) {
		OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri("https://idp.example/authorize")
				.clientId("hinata")
				.redirectUri("https://api.example/login/oauth2/code/oidc")
				.state(state)
				.build();
		requests.saveAuthorizationRequest(request, new MockHttpServletRequest(), new MockHttpServletResponse());
	}

	private static MockHttpServletRequest callback(String state, String code) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/oidc");
		request.setParameter("state", state);
		request.setParameter("code", code);
		return request;
	}

	@Test
	void onlyTheFirstArrivalTakesTheRequest() {
		pending("state-1");

		assertThat(requests.removeAuthorizationRequest(callback("state-1", "idp-code"),
				new MockHttpServletResponse())).isNotNull();
		assertThat(requests.removeAuthorizationRequest(callback("state-1", "idp-code"),
				new MockHttpServletResponse())).isNull();
		assertThat(requests.loadAuthorizationRequest(callback("state-1", "idp-code"))).isNull();
	}

	@Test
	void aDuplicateGetsTheFirstAnswer() {
		pending("state-2");
		MockHttpServletRequest first = callback("state-2", "idp-code");
		requests.removeAuthorizationRequest(first, new MockHttpServletResponse());
		replay.record(first, "hinata://auth-callback?code=handoff");

		assertThat(replay.replayFor(callback("state-2", "idp-code"), NOT_FOUND))
				.contains("hinata://auth-callback?code=handoff");
	}

	@Test
	void aDuplicateWaitsForTheFirstToAnswer() throws Exception {
		pending("state-3");
		MockHttpServletRequest first = callback("state-3", "idp-code");
		requests.removeAuthorizationRequest(first, new MockHttpServletResponse());

		CompletableFuture<Void> answer = CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(400);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			replay.record(first, "hinata://auth-callback?code=later");
		});

		assertThat(replay.replayFor(callback("state-3", "idp-code"), NOT_FOUND))
				.contains("hinata://auth-callback?code=later");
		answer.get(5, TimeUnit.SECONDS);
	}

	@Test
	void aFailedFirstArrivalIsAFailedDuplicate() {
		pending("state-4");
		MockHttpServletRequest first = callback("state-4", "idp-code");
		requests.removeAuthorizationRequest(first, new MockHttpServletResponse());
		replay.record(first, "hinata://auth-callback?error=invalid_token_response");

		assertThat(replay.replayFor(callback("state-4", "idp-code"), NOT_FOUND))
				.contains("hinata://auth-callback?error=invalid_token_response");
	}

	@Test
	void onlyAnExactDuplicateIsAnswered() {
		pending("state-5");
		MockHttpServletRequest first = callback("state-5", "idp-code");
		requests.removeAuthorizationRequest(first, new MockHttpServletResponse());
		replay.record(first, "hinata://auth-callback?code=handoff");

		// Another provider code under the same state is not the same callback.
		assertThat(replay.replayFor(callback("state-5", "other-code"), NOT_FOUND)).isEmpty();
		// A state nobody consumed has no first answer to give.
		assertThat(replay.replayFor(callback("never-issued", "idp-code"), NOT_FOUND)).isEmpty();
		// Any other failure is a failure.
		assertThat(replay.replayFor(callback("state-5", "idp-code"), new BadCredentialsException("no")))
				.isEmpty();
	}

	@Test
	void theFirstAnswerIsNotOverwritten() {
		pending("state-6");
		MockHttpServletRequest first = callback("state-6", "idp-code");
		requests.removeAuthorizationRequest(first, new MockHttpServletResponse());
		replay.record(first, "hinata://auth-callback?code=handoff");
		replay.record(first, "hinata://auth-callback?error=late");

		assertThat(replay.replayFor(callback("state-6", "idp-code"), NOT_FOUND))
				.contains("hinata://auth-callback?code=handoff");
	}

	@Test
	void aLateDuplicateIsNotAnswered() {
		pending("state-7");
		MockHttpServletRequest first = callback("state-7", "idp-code");
		requests.removeAuthorizationRequest(first, new MockHttpServletResponse());
		replay.record(first, "hinata://auth-callback?code=handoff");
		mongo.updateFirst(Query.query(Criteria.where("_id").is("state-7")),
				new Update().set("consumedAt",
						Instant.now().minus(SsoCallbackReplay.WINDOW).minus(Duration.ofSeconds(5))),
				PendingAuthorizationRequest.class);

		assertThat(replay.replayFor(callback("state-7", "idp-code"), NOT_FOUND)).isEmpty();
	}
}
