package com.ahmadre.hinata.common;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A wrong verb on a mapped URL is the client's 405, not the server's 500. */
class GlobalExceptionHandlerMethodTest {

	@Test
	void aWrongMethodIsAFourOhFiveNamingTheAllowedOnes() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler(new StaticMessageSource());
		HttpRequestMethodNotSupportedException wrong =
				new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "PATCH", "DELETE"));

		ResponseEntity<GlobalExceptionHandler.ApiError> answer = handler.handleWrongMethod(wrong,
				new MockHttpServletRequest("PUT", "/api/v1/issues/x"), new MockHttpServletResponse());

		assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(answer.getHeaders().getAllow())
				.containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE);
	}
}
