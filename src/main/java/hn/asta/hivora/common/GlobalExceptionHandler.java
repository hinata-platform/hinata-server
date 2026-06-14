package hn.asta.hivora.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps exceptions to a stable JSON error shape without ever leaking
 * stack traces or internals to the client (OWASP A05/A09). Messages are
 * localized against {@code messages*.properties} using the request locale
 * (Accept-Language).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	private final MessageSource messages;

	public GlobalExceptionHandler(MessageSource messages) {
		this.messages = messages;
	}

	public record ApiError(int status, String error, String message, Instant timestamp,
			Map<String, String> fieldErrors) {

		static ApiError of(HttpStatus status, String message, Map<String, String> fieldErrors) {
			return new ApiError(status.value(), status.getReasonPhrase(), message, Instant.now(), fieldErrors);
		}
	}

	/** Resolves an i18n key for the current request locale, falling back to the key itself. */
	private String t(String key, Object... args) {
		Locale locale = LocaleContextHolder.getLocale();
		return messages.getMessage(key, args, key, locale);
	}

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApi(ApiException ex) {
		String message = t(ex.getMessageKey(), ex.getArgs());
		return ResponseEntity.status(ex.getStatus()).body(ApiError.of(ex.getStatus(), message, null));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> fields = new HashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
		return ResponseEntity.badRequest()
				.body(ApiError.of(HttpStatus.BAD_REQUEST, t("error.validationFailed"), fields));
	}

	/**
	 * Malformed JSON, a wrong field type (e.g. an object where a string is
	 * expected) or an unreadable body is a client error — return 400, not 500
	 * (OWASP A04/A09). No parser detail is echoed back.
	 */
	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleUnreadable(
			org.springframework.http.converter.HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest()
				.body(ApiError.of(HttpStatus.BAD_REQUEST, t("error.malformedBody"), null));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException ex) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(ApiError.of(HttpStatus.PAYLOAD_TOO_LARGE, t("error.uploadTooLarge"), null));
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiError> handleAuth(AuthenticationException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(ApiError.of(HttpStatus.UNAUTHORIZED, t("error.auth.required"), null));
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ApiError.of(HttpStatus.FORBIDDEN, t("error.accessDenied"), null));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, t("error.internal"), null));
	}
}
