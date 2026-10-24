package com.ahmadre.hinata.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * An address people are sent to from a public page or a mail: absolute, {@code https} and with
 * a host. Plain {@code http} would let anybody on the way swap the page, and any other scheme
 * ({@code javascript:}, {@code data:}) is no page at all. Empty means "not set" and passes.
 */
@Documented
@Constraint(validatedBy = HttpsUrl.Validator.class)
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpsUrl {

	String message() default "error.validation.httpsUrl";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<HttpsUrl, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			if (value == null || value.isBlank()) {
				return true;
			}
			try {
				URI uri = new URI(value.trim());
				return uri.isAbsolute() && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
						&& uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null;
			}
			catch (URISyntaxException notAnAddress) {
				return false;
			}
		}
	}
}
