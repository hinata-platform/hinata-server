package hn.asta.hivora.auth.sso;

import hn.asta.hivora.setup.ServerSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Component;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.util.Optional;

/**
 * Bind-authenticates a user against the LDAP server configured at runtime in
 * the admin area. Returns the user's e-mail and display name on success.
 */
@Slf4j
@Component
public class LdapAuthenticator {

	public record LdapUser(String email, String displayName) {
	}

	public Optional<LdapUser> authenticate(ServerSettings.Ldap config, String username, String password) {
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
			String email = attributeValue(attributes, config.getEmailAttribute())
					.orElse(username + "@ldap.local");
			String displayName = attributeValue(attributes, config.getDisplayNameAttribute()).orElse(username);
			return Optional.of(new LdapUser(email, displayName));
		}
		catch (BadCredentialsException ex) {
			return Optional.empty();
		}
		catch (Exception ex) {
			log.warn("LDAP authentication failed: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	private Optional<String> attributeValue(Attributes attributes, String name) {
		try {
			Attribute attribute = attributes.get(name);
			return attribute != null ? Optional.ofNullable((String) attribute.get()) : Optional.empty();
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}
}
