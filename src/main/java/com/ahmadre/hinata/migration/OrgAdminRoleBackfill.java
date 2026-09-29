package com.ahmadre.hinata.migration;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time hand-over when the organisation admin role arrives: every account that
 * is an administrator at that moment also becomes an organisation admin.
 *
 * <p>Until then an administrator did both jobs — the platform and the working
 * time, approvals, absences and billing. The second job moved to the new role, and
 * an instance whose nobody held it would wake up with nobody who may decide a
 * timesheet or keep absences. Handing it to the same people keeps every instance
 * working exactly as before; separating the two is then a decision in Admin →
 * Users, made on purpose.
 *
 * <p>Runs once (a marker in {@code migrations}), never fails the start, and only
 * ever adds the role — a later run could not take it from anybody who was given it
 * or kept it deliberately.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class OrgAdminRoleBackfill implements ApplicationRunner {

	static final String MARKER_ID = "org-admin-role-from-admins";

	private final MongoTemplate mongo;
	private final MigrationMarkers markers;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) return;
		try {
			long granted = mongo.getCollection("users").updateMany(
					Filters.and(Filters.eq("roles", "ADMIN"), Filters.ne("roles", "ORG_ADMIN")),
					Updates.addToSet("roles", "ORG_ADMIN")).getModifiedCount();
			if (granted > 0) {
				log.info("OrgAdminRoleBackfill: {} administrator(s) are now also organisation admins", granted);
			}
			markers.markDone(MARKER_ID, new Document("granted", granted));
		}
		catch (RuntimeException ex) {
			log.warn("OrgAdminRoleBackfill failed; will retry on next start", ex);
		}
	}
}
