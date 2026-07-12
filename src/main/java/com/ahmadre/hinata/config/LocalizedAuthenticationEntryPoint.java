package com.ahmadre.hinata.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns localized JSON errors for authentication failures that happen in the
 * Spring Security filter chain and therefore bypass {@code @RestControllerAdvice}.
 */
@Component
@RequiredArgsConstructor
public class LocalizedAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final MessageSource messages;

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		if (response.isCommitted()) return;
		String key = messageKey(exception);
		String message = messages.getMessage(key, null, key, request.getLocale());
		LocalizedErrorResponse.write(response, org.springframework.http.HttpStatus.UNAUTHORIZED, message);
	}

	private static String messageKey(AuthenticationException exception) {
		if (exception instanceof OAuth2AuthenticationException oauth) {
			String description = oauth.getError().getDescription();
			if (description != null && description.startsWith("error.")) {
				return description;
			}
		}
		return "error.auth.required";
	}
}
