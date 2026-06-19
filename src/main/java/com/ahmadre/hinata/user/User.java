package com.ahmadre.hinata.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@Document("users")
public class User {

	public enum Origin { LOCAL, OIDC, SAML, LDAP }

	@Id
	private String id;

	@Indexed(unique = true)
	private String email;

	@Indexed(unique = true)
	@TextIndexed(weight = 5)
	private String username;

	@TextIndexed(weight = 10)
	private String displayName;

	/** BCrypt hash; null for SSO-provisioned accounts. Never serialized. */
	@JsonIgnore
	private String passwordHash;

	@Builder.Default
	private Set<Role> roles = new HashSet<>(Set.of(Role.MEMBER));

	@Builder.Default
	private Origin origin = Origin.LOCAL;

	private String avatarUrl;

	/** Job title shown e.g. in the dashboard performance ranking. */
	@TextIndexed(weight = 3)
	private String title;

	@Builder.Default
	private String locale = "en";

	@Builder.Default
	private boolean active = true;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public boolean isAdmin() {
		return roles != null && roles.contains(Role.ADMIN);
	}
}
