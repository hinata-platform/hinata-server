package com.ahmadre.hinata.setup;

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

/**
 * Constrains {@code ServerSettings.General.logoUrl} to the two shapes the
 * product actually supports: an absolute {@code http}/{@code https} URL the
 * server proxies, or the internal path an upload writes.
 *
 * <p>This matters because {@code /api/v1/meta} republishes the value to
 * unauthenticated clients. Without it a stored {@code javascript:…} or
 * {@code data:text/html,…} would sit in the settings document waiting for the
 * first surface that renders {@code logoUrl} directly instead of dereferencing
 * it through the proxy. Blank is allowed — that is "no logo".
 */
@Documented
@Constraint(validatedBy = LogoUrl.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface LogoUrl {

	String message() default "error.logo.invalidUrl";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<LogoUrl, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			if (value == null || value.isBlank()) {
				return true;
			}
			String url = value.trim();
			if (!OrganizationLogoService.isInternal(url)) {
				try {
					URI uri = new URI(url);
					String scheme = uri.getScheme();
					return scheme != null
							&& (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
							&& uri.getHost() != null && !uri.getHost().isBlank();
				}
				catch (Exception ex) {
					return false;
				}
			}
			// Internal: exactly the proxy path an upload writes, optionally with the
			// cache-busting token. Anything else ("//evil.com/x.svg", "javascript:…",
			// a relative path into another endpoint) is not a logo we wrote.
			return url.equals(OrganizationLogoService.INTERNAL_URL_PREFIX)
					|| url.startsWith(OrganizationLogoService.INTERNAL_URL_PREFIX + "?v=");
		}
	}
}
