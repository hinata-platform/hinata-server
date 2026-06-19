package com.ahmadre.hinata.common;

import lombok.Getter;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;

/**
 * Carries an i18n message <em>key</em> (and optional format arguments) rather
 * than a literal string. {@link GlobalExceptionHandler} resolves the key against
 * the {@code messages*.properties} bundles using the request locale
 * (Accept-Language), so clients receive the message in their own language.
 */
@Getter
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	/** Key into {@code messages*.properties}, e.g. {@code error.project.notMember}. */
	private final String messageKey;

	/** Optional {@link java.text.MessageFormat} arguments for the resolved message. */
	private final transient Object[] args;

	public ApiException(HttpStatus status, String messageKey, Object... args) {
		// Keep the key as the exception's message so logs stay meaningful even
		// before localization.
		super(messageKey);
		this.status = status;
		this.messageKey = messageKey;
		this.args = args;
	}

	/**
	 * 404 for a missing entity. [entityKey] names an {@code entity.*} message
	 * (e.g. {@code "project"}); it is resolved and substituted into the localized
	 * {@code error.notFound} template ("{0} not found").
	 */
	public static ApiException notFound(String entityKey) {
		return new ApiException(HttpStatus.NOT_FOUND, "error.notFound",
				new DefaultMessageSourceResolvable("entity." + entityKey));
	}

	public static ApiException badRequest(String messageKey, Object... args) {
		return new ApiException(HttpStatus.BAD_REQUEST, messageKey, args);
	}

	public static ApiException forbidden(String messageKey, Object... args) {
		return new ApiException(HttpStatus.FORBIDDEN, messageKey, args);
	}

	public static ApiException conflict(String messageKey, Object... args) {
		return new ApiException(HttpStatus.CONFLICT, messageKey, args);
	}

	public static ApiException unauthorized(String messageKey, Object... args) {
		return new ApiException(HttpStatus.UNAUTHORIZED, messageKey, args);
	}
}
