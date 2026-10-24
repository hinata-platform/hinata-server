package com.ahmadre.hinata.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accepts the house rules over the REST API, as the app does right after signing in (HIN-175):
 * until then nobody gets a new access token or gives an OAuth client consent.
 */
public final class TestRules {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private TestRules() {
	}

	/** Accepts the current version for the person [jwt] belongs to. */
	public static void accept(int port, String jwt) {
		try {
			HttpResponse<String> rules = HTTP.send(request(port, "/api/v1/me/rules", jwt).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(rules.statusCode()).as("read rules").isEqualTo(200);
			int version = new ObjectMapper().readTree(rules.body()).path("version").asInt();
			HttpResponse<String> accepted = HTTP.send(request(port, "/api/v1/me/rules/acceptance", jwt)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"version\":" + version + "}")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(accepted.statusCode()).as("accept rules").isEqualTo(204);
		}
		catch (Exception e) {
			throw new AssertionError("Accepting the house rules failed", e);
		}
	}

	private static HttpRequest.Builder request(int port, String path, String jwt) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + jwt);
	}
}
