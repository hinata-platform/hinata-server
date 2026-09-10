package com.ahmadre.hinata.common;

import lombok.Getter;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;

import java.util.Map;

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

	/**
	 * Machine-readable facts about the refusal, sent beside the sentence.
	 *
	 * <p>Empty for almost every error, and meant to stay that way: a localized
	 * message is what a client shows, and a details map that duplicated it would
	 * be a second contract to keep in step. It exists for refusals a client has to
	 * <em>act</em> on rather than print — the frozen entry of HIN-88, which names
	 * why it is frozen, who can lift it and what the way back is, so the app can
	 * offer that way instead of leaving somebody at a dead end.
	 *
	 * <p>Additive on the wire and omitted when empty, so a published client that
	 * has never heard of it is unaffected.
	 */
	private final transient Map<String, String> details;

	public ApiException(HttpStatus status, String messageKey, Object... args) {
		this(status, messageKey, Map.of(), args);
	}

	public ApiException(HttpStatus status, String messageKey, Map<String, String> details,
			Object... args) {
		// Keep the key as the exception's message so logs stay meaningful even
		// before localization.
		super(messageKey);
		this.status = status;
		this.messageKey = messageKey;
		this.args = args;
		this.details = details == null ? Map.of() : Map.copyOf(details);
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

	/** A 403 the client can act on: see {@link #getDetails()}. */
	public static ApiException forbidden(String messageKey, Map<String, String> details,
			Object... args) {
		return new ApiException(HttpStatus.FORBIDDEN, messageKey, details, args);
	}

	public static ApiException conflict(String messageKey, Object... args) {
		return new ApiException(HttpStatus.CONFLICT, messageKey, args);
	}

	public static ApiException unauthorized(String messageKey, Object... args) {
		return new ApiException(HttpStatus.UNAUTHORIZED, messageKey, args);
	}
}
