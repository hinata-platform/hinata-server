package com.ahmadre.hinata.config;

import com.ahmadre.hinata.common.TestMongo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UnauthenticatedSurfaceTest} catches what it is there for: a controller route and a router
 * function under paths the security rules leave open both answer without a token, and both are
 * found, while everything reviewed stays quiet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
@Import({UnauthenticatedSurfaceProbeTest.OpenController.class, UnauthenticatedSurfaceProbeTest.OpenRouter.class})
class UnauthenticatedSurfaceProbeTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@LocalServerPort
	private int port;
	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("a new route under an open pattern, annotated or functional, fails the check")
	void newOpenRoutesAreFound() {
		assertThat(ServerSurface.answeredWithoutToken(ServerSurface.routes(context),
				UnauthenticatedSurfaceTest.PUBLIC_ROUTES, port))
				.extracting(finding -> finding.substring(0, finding.indexOf("  (")))
				.containsExactlyInAnyOrder("GET /api/v1/auth/invite/probe", "GET /api/v1/auth/reset/probe");
	}

	/** Lands under {@code /api/v1/auth/invite/**}, which the rules open for the invitation flow. */
	@RestController
	static class OpenController {

		@GetMapping("/api/v1/auth/invite/probe")
		String probe() {
			return "open";
		}
	}

	/** Lands under {@code /api/v1/auth/reset/**} as a router function, the way the MCP transport is mapped. */
	@TestConfiguration(proxyBeanMethods = false)
	static class OpenRouter {

		@Bean
		RouterFunction<ServerResponse> openProbeRoute() {
			return RouterFunctions.route()
					.GET("/api/v1/auth/reset/probe", request -> ServerResponse.ok().body("open"))
					.build();
		}
	}
}
