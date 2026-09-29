package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.admin.AdminUserService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
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
 * or kept it deliberately. Each grant is audited and the person is told, so the
 * hand-over is visible to them and to whoever reads the log, not only to the
 * operator who reads the server's output.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class OrgAdminRoleBackfill implements ApplicationRunner {

	static final String MARKER_ID = "org-admin-role-from-admins";

	private final UserRepository users;
	private final MigrationMarkers markers;
	private final AdminUserService adminUsers;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) return;
		try {
			int granted = 0;
			for (User user : users.findByRolesContaining(Role.ADMIN)) {
				if (user.isOrgAdmin()) continue;
				// The same grant as in Admin → Users: one record per person (reason
				// "migration") and their own notice, findable by name rather than as a
				// count in a log line.
				adminUsers.changeOrgRole(user, true, null, "migration");
				granted++;
			}
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
