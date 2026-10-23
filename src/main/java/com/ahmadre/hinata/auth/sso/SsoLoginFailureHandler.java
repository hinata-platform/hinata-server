package com.ahmadre.hinata.auth.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * SSO failures on a pure API server must not end on Spring's default
 * {@code /login?error} page (it does not exist). Logs the root cause and sends
 * the browser back into the app with a readable error code instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoLoginFailureHandler implements AuthenticationFailureHandler {

	private final SsoReturn back;
	private final SsoCallbackReplay replay;

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		// A second arrival of a callback that already went through is not a failure: it gets the
		// first one's answer, or it would race that answer into the app as an error.
		var replayed = replay.replayFor(request, exception);
		if (replayed.isPresent()) {
			response.sendRedirect(replayed.get());
			return;
		}
		Throwable root = exception;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		log.error("SSO login failed: {} (root cause: {})", exception.getMessage(), root.toString(), exception);

		back.failed(request, response, trim(exception.getMessage()));
	}

	private String trim(String message) {
		if (message == null || message.isBlank()) {
			return "sso_failed";
		}
		return message.length() > 200 ? message.substring(0, 200) : message;
	}
}
