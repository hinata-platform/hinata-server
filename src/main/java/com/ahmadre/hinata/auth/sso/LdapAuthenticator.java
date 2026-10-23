package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.user.SsoIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Component;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bind-authenticates a user against the LDAP server configured at runtime in
 * the admin area. On success the entry's attributes are mapped onto a profile
 * (e-mail, display name, position) by the same admin-configured mapping every
 * other identity provider uses — see {@link SsoProfileMapper}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LdapAuthenticator {

	/** The registration id an LDAP sign-in is bound under, beside {@code oidc}, {@code oauth2} and {@code saml}. */
	public static final String LDAP_ID = "ldap";

	/**
	 * The entry's own id, which survives a rename and a move to another branch of the tree:
	 * {@code entryUUID} (OpenLDAP, 389 DS, Synology Directory Server and most others) and
	 * {@code objectGUID} (Active Directory). {@code entryUUID} is an operational attribute, which
	 * a directory sends only to a search that names it, so the user search asks for both by name
	 * next to every user attribute ({@code *}).
	 */
	private static final String ENTRY_UUID = "entryUUID";
	private static final String OBJECT_GUID = "objectGUID";
	private static final String[] ENTRY_ATTRIBUTES = { "*", ENTRY_UUID, OBJECT_GUID };

	private final SsoProfileMapper mapper;

	/**
	 * A successful bind: the key the account is bound to (see {@link #subjectOf}) and the mapped
	 * profile.
	 */
	public record LdapLogin(SsoIdentity identity, SsoProfileMapper.SsoProfile profile) {
	}

	public Optional<LdapLogin> authenticate(ServerSettings.Ldap config, String username,
			String password) {
		if (!config.isEnabled() || config.getUrl() == null || password == null || password.isBlank()) {
			return Optional.empty();
		}
		try {
			LdapContextSource contextSource = new LdapContextSource();
			contextSource.setUrl(config.getUrl());
			contextSource.setBase(config.getBaseDn());
			// objectGUID is sixteen raw bytes; without this JNDI decodes them as text.
			contextSource.setBaseEnvironmentProperties(Map.of("java.naming.ldap.attributes.binary", OBJECT_GUID));
			if (config.getManagerDn() != null && !config.getManagerDn().isBlank()) {
				contextSource.setUserDn(config.getManagerDn());
				contextSource.setPassword(config.getManagerPassword());
			}
			contextSource.afterPropertiesSet();

			BindAuthenticator authenticator = new BindAuthenticator(contextSource);
			// The bind takes the entry with the attributes the search returned.
			FilterBasedLdapUserSearch search = new FilterBasedLdapUserSearch(
					config.getUserSearchBase(), config.getUserSearchFilter(), contextSource);
			search.setReturningAttributes(ENTRY_ATTRIBUTES);
			authenticator.setUserSearch(search);

			DirContextOperations entry = authenticator
					.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
			SsoProfileMapper.SsoProfile profile = mapper.map(toMap(entry.getAttributes()), config,
					username + "@ldap.local");
			SsoIdentity identity = new SsoIdentity(LDAP_ID, directoryOf(config), subjectOf(entry));
			return Optional.of(new LdapLogin(identity, profile.displayName() != null ? profile
					: new SsoProfileMapper.SsoProfile(profile.email(), username, profile.title())));
		}
		catch (BadCredentialsException ex) {
			return Optional.empty();
		}
		catch (Exception ex) {
			log.warn("LDAP authentication failed: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * The key an account is bound to: the entry's own id where the directory has one, so a
	 * renamed or moved entry still finds its account; otherwise its DN, which a rename or a move
	 * changes (the person then needs an administrator to undo the binding). Distinguished names
	 * compare case-insensitively, and the directory may spell one differently from one bind to
	 * the next.
	 */
	private static String subjectOf(DirContextOperations entry) {
		String uuid = entry.getStringAttribute(ENTRY_UUID);
		if (uuid != null && !uuid.isBlank()) {
			return uuid.toLowerCase(Locale.ROOT);
		}
		if (entry.getObjectAttribute(OBJECT_GUID) instanceof byte[] guid && guid.length > 0) {
			return java.util.Base64.getEncoder().encodeToString(guid);
		}
		return entry.getNameInNamespace().toLowerCase(Locale.ROOT);
	}

	/**
	 * The directory a sign-in comes from, named by its base DN: the same directory reached
	 * under another address (ldaps instead of ldap, a second server) is no switch of provider.
	 */
	private static String directoryOf(ServerSettings.Ldap config) {
		String base = config.getBaseDn();
		return base != null && !base.isBlank() ? base.trim().toLowerCase(Locale.ROOT) : config.getUrl();
	}

	/**
	 * Flattens the JNDI entry into a plain attribute map so the shared profile
	 * mapper can read an LDAP entry exactly like a set of OIDC claims. A single
	 * value stays scalar; a multi-valued attribute becomes a list.
	 */
	private Map<String, Object> toMap(Attributes attributes) {
		Map<String, Object> map = new LinkedHashMap<>();
		try {
			for (NamingEnumeration<? extends Attribute> all = attributes.getAll(); all.hasMore();) {
				Attribute attribute = all.next();
				List<Object> values = new ArrayList<>();
				for (NamingEnumeration<?> value = attribute.getAll(); value.hasMore();) {
					values.add(value.next());
				}
				if (!values.isEmpty()) {
					map.put(attribute.getID(), values.size() == 1 ? values.get(0) : values);
				}
			}
		}
		catch (Exception ex) {
			log.warn("Reading LDAP attributes failed: {}", ex.getMessage());
		}
		return map;
	}
}
