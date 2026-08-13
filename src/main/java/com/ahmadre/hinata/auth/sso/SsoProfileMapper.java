package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.setup.ServerSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Maps whatever an identity provider hands us — OIDC/OAuth2 claims, SAML
 * assertion attributes, LDAP entry attributes — onto the three profile fields
 * Hinata stores: e-mail, display name and job title ("Position").
 *
 * <p>Beyond {@code email} there is no attribute every IdP agrees on, so the
 * mapping is not hardcoded: each provider block in the admin area carries it as
 * a priority list that an administrator can adapt to their directory.
 *
 * <pre>
 *   name, preferred_username, given_name family_name
 *       └── comma: alternatives, the first non-blank one wins
 *                                     └── space: join, only if every part is present
 * </pre>
 *
 * <p>Nothing is ever invented: an attribute the IdP does not send yields
 * {@code null}, and a {@code null} never overwrites a stored value (see
 * {@link com.ahmadre.hinata.user.UserService#provisionSso}). Attribute names are
 * matched case-insensitively as a fallback, because directories disagree on
 * casing ({@code displayName} vs {@code displayname}) far more often than they
 * disagree on meaning.
 */
@Slf4j
@Component
public class SsoProfileMapper {

	/**
	 * A resolved SSO profile. {@code email} is the account key and always set;
	 * {@code displayName} and {@code title} are {@code null} when the IdP sends
	 * nothing usable for them.
	 */
	public record SsoProfile(String email, String displayName, String title) {
	}

	/**
	 * Resolves the profile from the IdP's attributes using the admin-configured
	 * mapping. [fallbackEmail] is the principal's own name, used only when the
	 * configured e-mail attribute is absent.
	 */
	public SsoProfile map(Map<String, Object> attributes, ServerSettings.AttributeMapping mapping,
			String fallbackEmail) {
		Map<String, Object> source = attributes != null ? attributes : Map.of();
		if (log.isDebugEnabled()) {
			// Attribute *names* are all it takes to fix a mapping that came up empty,
			// so they are the DEBUG level.
			log.debug("SSO attributes received: {}", source.keySet());
		}
		if (log.isTraceEnabled()) {
			// The values are personal data, so they sit one level deeper: enabling
			// DEBUG for this package can never dump them by accident, and TRACE is a
			// deliberate act for a single debugging session. Turn it off afterwards.
			source.forEach((name, value) -> log.trace("SSO attribute {} ({}) = {}",
					name, typeOf(value), preview(value)));
		}
		String email = resolve(source, mapping.getEmailAttribute());
		if (isBlank(email)) {
			email = fallbackEmail;
		}
		String displayName = resolve(source, mapping.getDisplayNameAttribute());
		if (isBlank(displayName)) {
			displayName = humanize(email);
		}
		return new SsoProfile(email, displayName, resolve(source, mapping.getTitleAttribute()));
	}

	/**
	 * Type of a raw attribute, so a TRACE line shows <em>why</em> a mapping missed:
	 * a claim arriving as a list or a nested object reads differently from the
	 * plain string an admin expected.
	 */
	private String typeOf(Object value) {
		return value == null ? "null" : value.getClass().getSimpleName();
	}

	/** Raw attribute rendered for a TRACE line, capped so one claim cannot flood the log. */
	private String preview(Object value) {
		String text = value instanceof Object[] array ? java.util.Arrays.toString(array)
				: String.valueOf(value);
		return text.length() <= TRACE_VALUE_LIMIT
				? text
				: text.substring(0, TRACE_VALUE_LIMIT) + "… (" + text.length() + " chars)";
	}

	private static final int TRACE_VALUE_LIMIT = 300;

	/** First candidate of the comma-separated [spec] that resolves to a value. */
	private String resolve(Map<String, Object> attributes, String spec) {
		if (isBlank(spec)) {
			return null;
		}
		for (String candidate : spec.split(",")) {
			String value = compose(attributes, candidate.trim());
			if (!isBlank(value)) {
				return value;
			}
		}
		return null;
	}

	/**
	 * One candidate: a single attribute name, or several joined by spaces. A
	 * composite is all-or-nothing — half a name ("Grace ") is worse than falling
	 * through to the next alternative.
	 */
	private String compose(Map<String, Object> attributes, String candidate) {
		if (candidate.isEmpty()) {
			return null;
		}
		StringBuilder joined = new StringBuilder();
		for (String name : candidate.split("\\s+")) {
			String part = value(attributes, name);
			if (isBlank(part)) {
				return null;
			}
			if (!joined.isEmpty()) {
				joined.append(' ');
			}
			joined.append(part);
		}
		return joined.toString();
	}

	/** Exact attribute lookup, falling back to a case-insensitive match. */
	private String value(Map<String, Object> attributes, String name) {
		Object value = attributes.get(name);
		if (value == null) {
			for (Map.Entry<String, Object> entry : attributes.entrySet()) {
				if (name.equalsIgnoreCase(entry.getKey())) {
					value = entry.getValue();
					break;
				}
			}
		}
		return stringify(value);
	}

	/** Flattens a claim value; multi-valued attributes (SAML, LDAP) yield the first. */
	private String stringify(Object value) {
		if (value instanceof String text) {
			return text.trim();
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		if (value instanceof Collection<?> values) {
			for (Object element : values) {
				String text = stringify(element);
				if (!isBlank(text)) {
					return text;
				}
			}
		}
		if (value instanceof Object[] values) {
			for (Object element : values) {
				String text = stringify(element);
				if (!isBlank(text)) {
					return text;
				}
			}
		}
		return null;
	}

	/**
	 * Last-resort display name derived from the address itself:
	 * {@code ada.lovelace@example.org} → {@code Ada Lovelace}. Applied only when
	 * the local part actually reads like a name — letter-only segments separated
	 * by {@code . _ -} — so role mailboxes ({@code it@…}) and handles
	 * ({@code a1b2c3@…}) keep the plain address instead of being dressed up as a
	 * person. Returns {@code null} when nothing can be derived.
	 */
	private String humanize(String email) {
		if (isBlank(email)) {
			return null;
		}
		int at = email.indexOf('@');
		String local = at > 0 ? email.substring(0, at) : email;
		String[] parts = local.split("[._-]+");
		if (parts.length < 2) {
			return null;
		}
		StringBuilder name = new StringBuilder();
		for (String part : parts) {
			if (part.length() < 2 || !part.chars().allMatch(Character::isLetter)) {
				return null;
			}
			if (!name.isEmpty()) {
				name.append(' ');
			}
			name.append(Character.toUpperCase(part.charAt(0)))
					.append(part.substring(1).toLowerCase(Locale.ROOT));
		}
		return name.toString();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
