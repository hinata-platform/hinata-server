package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.setup.ServerSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

	private final SsoProfileMapper mapper;

	public Optional<SsoProfileMapper.SsoProfile> authenticate(ServerSettings.Ldap config, String username,
			String password) {
		if (!config.isEnabled() || config.getUrl() == null || password == null || password.isBlank()) {
			return Optional.empty();
		}
		try {
			LdapContextSource contextSource = new LdapContextSource();
			contextSource.setUrl(config.getUrl());
			contextSource.setBase(config.getBaseDn());
			if (config.getManagerDn() != null && !config.getManagerDn().isBlank()) {
				contextSource.setUserDn(config.getManagerDn());
				contextSource.setPassword(config.getManagerPassword());
			}
			contextSource.afterPropertiesSet();

			BindAuthenticator authenticator = new BindAuthenticator(contextSource);
			authenticator.setUserSearch(new FilterBasedLdapUserSearch(
					config.getUserSearchBase(), config.getUserSearchFilter(), contextSource));

			Attributes attributes = authenticator
					.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password))
					.getAttributes();
			SsoProfileMapper.SsoProfile profile = mapper.map(toMap(attributes), config,
					username + "@ldap.local");
			return Optional.of(profile.displayName() != null ? profile
					: new SsoProfileMapper.SsoProfile(profile.email(), username, profile.title()));
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
