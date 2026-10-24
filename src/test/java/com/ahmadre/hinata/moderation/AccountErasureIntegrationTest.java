package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.admin.AdminUserService;
import com.ahmadre.hinata.me.AvatarService;
import com.ahmadre.hinata.me.RefreshSession;
import com.ahmadre.hinata.oauth.OAuthAuthorizationCode;
import com.ahmadre.hinata.oauth.OAuthGrant;
import com.ahmadre.hinata.pat.PersonalAccessToken;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing that lets a deleted account in, or shows it, stays behind (HIN-172, Art. 17 DSGVO): no
 * session, no access token, no OAuth grant or code, no uploaded picture.
 */
class AccountErasureIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	private AdminUserService adminUsers;

	@Test
	void anAdministratorDeletingAnAccountLeavesNothingOfItsWayIn() {
		for (String collection : List.of("sessions", "personal_access_tokens", "oauth_grants", "oauth_codes")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		User admin = user("ada", Role.ADMIN);
		Instant now = Instant.now();
		mongo.insert(RefreshSession.builder().userId(author.getId()).createdAt(now).build());
		mongo.insert(PersonalAccessToken.builder().userId(author.getId()).name("Skript").tokenHash("hash-pat")
				.createdAt(now).build());
		mongo.insert(OAuthGrant.builder().userId(author.getId()).clientId("client").refreshTokenHash("hash-grant")
				.createdAt(now).build());
		mongo.insert(OAuthAuthorizationCode.builder().userId(author.getId()).clientId("client").createdAt(now).build());
		store.put(AvatarService.objectKey(author.getId()), "jpeg");
		actingAs(admin);

		adminUsers.delete(List.of(author.getId()));

		Query theirs = Query.query(Criteria.where("userId").is(author.getId()));
		assertThat(mongo.count(theirs, RefreshSession.class)).as("sessions").isZero();
		assertThat(mongo.count(theirs, PersonalAccessToken.class)).as("access tokens").isZero();
		assertThat(mongo.count(theirs, OAuthGrant.class)).as("grants").isZero();
		assertThat(mongo.count(theirs, OAuthAuthorizationCode.class)).as("codes").isZero();
		assertThat(store.has(AvatarService.objectKey(author.getId()))).as("picture").isFalse();
		assertThat(users.findById(author.getId())).isEmpty();
	}
}
