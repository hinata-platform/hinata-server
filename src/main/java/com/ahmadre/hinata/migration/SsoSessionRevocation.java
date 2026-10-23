package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.AuthPolicy;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * One-time sign-out of every account an SSO or LDAP sign-in could have entered by its address
 * alone, before HIN-179 bound accounts to the provider's key for the person.
 *
 * <p>Until then anybody who could put an address into their profile at the identity provider
 * signed in as whoever owned it in Hinata. A session obtained that way would outlive the fix:
 * refresh tokens slide, so it stays as long as it is used. Ending the sessions of the accounts
 * that were reachable that way ends any such session, and the next sign-in of each person binds
 * their account to them, which closes the window in which the address still decides as soon as
 * possible.
 *
 * <p>While any SSO or LDAP sign-in is on, or local sign-in is off, that is everybody: whose
 * account such a sign-in entered is only known from the audit log, which can be switched off and
 * forgets after a year. With all of them off, it is every account they made and every account
 * the audit log saw one enter while they were on. Only sessions opened before the migration
 * started end, so nobody who signs in while it runs is signed out again. Personal access tokens
 * and connected AI clients stay; whether one of those was set up by somebody else is a question
 * for the audit log, not for a migration.
 *
 * <p>Runs once (a marker in {@code migrations}) and never fails the start.
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class SsoSessionRevocation implements ApplicationRunner {

	static final String MARKER_ID = "hin179-sign-out-address-reachable-accounts";

	private final MongoTemplate mongo;
	private final MigrationMarkers markers;
	private final SessionService sessions;
	private final SettingsService settings;
	private final AuthPolicy authPolicy;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) return;
		try {
			Instant started = Instant.now();
			Optional<Set<String>> reachable = reachable();
			int ended = sessions.revokeOpenedBefore(started, reachable.orElse(null));
			log.info("SsoSessionRevocation: ended {} session(s) of {}, the accounts an SSO or LDAP sign-in could "
					+ "have entered by address alone", ended,
					reachable.map(ids -> ids.size() + " account(s)").orElse("all accounts"));
			markers.markDone(MARKER_ID, new Document("sessions", ended)
					.append("accounts", reachable.map(Set::size).orElse(-1)));
		}
		catch (RuntimeException ex) {
			log.warn("SsoSessionRevocation failed; will retry on next start", ex);
		}
	}

	/** The accounts to sign out, or empty for everybody. */
	private Optional<Set<String>> reachable() {
		ServerSettings current = settings.get();
		if (current.getOidc().isEnabled() || current.getOauth2().isEnabled() || current.getSaml().isEnabled()
				|| current.getLdap().isEnabled() || !authPolicy.localAuthEnabled()) {
			return Optional.empty();
		}
		Set<String> ids = new HashSet<>();
		Query accounts = Query.query(Criteria.where("origin").ne(User.Origin.LOCAL.name()));
		// Ids only, as raw documents: a user mapped from nothing but its id fails on its flags.
		accounts.fields().include("_id");
		mongo.find(accounts, Document.class, mongo.getCollectionName(User.class))
				.forEach(user -> ids.add(String.valueOf(user.get("_id"))));
		ids.addAll(mongo.findDistinct(Query.query(Criteria.where("action").is(AuditAction.SSO_LOGIN.name())),
				"actorId", AuditLog.class, String.class));
		ids.remove(null);
		return Optional.of(ids);
	}
}
