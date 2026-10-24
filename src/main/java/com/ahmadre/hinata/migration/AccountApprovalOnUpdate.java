package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.AccountAdmission;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
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

import java.util.List;

/**
 * One-time step of the update that brings moderation (HIN-175): new accounts need approval from
 * now on, after a self-registration and after a first sign-in through SSO alike.
 *
 * <p>An account that comes in without anybody deciding reads what the organisation shares with
 * everybody, and under the DSA (recital 14) that makes the content public, with the duties of an
 * online platform. So every way in that let people in by itself is closed, each one audited with
 * the reason {@code migration}, and every administrator is told why and where to open it again,
 * which takes a confirmation on purpose. A new instance gets the same from its setup instead.
 *
 * <p>Runs once (a marker in {@code migrations}) and never fails the start.
 */
@Slf4j
@Component
@Order(46)
@RequiredArgsConstructor
public class AccountApprovalOnUpdate implements ApplicationRunner {

	static final String MARKER_ID = "account-approval-on-update";

	private final MigrationMarkers markers;
	private final SettingsService settings;
	private final AccountAdmission admission;
	private final AuditService audit;
	private final UserRepository users;
	private final NotificationService notifications;
	private final UserWords words;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) {
			return;
		}
		try {
			ServerSettings current = settings.get();
			if (!current.isSetupCompleted()) {
				// The setup turns approval on for a new instance itself.
				markers.markDone(MARKER_ID, new Document("closed", 0));
				return;
			}
			List<AccountAdmission.OpenWay> closed = admission.requireApprovalEverywhere(current);
			settings.save(current);
			for (AccountAdmission.OpenWay way : closed) {
				audit.event(AuditAction.ACCOUNT_APPROVAL_CHANGED).meta("way", way.way()).meta("open", "false")
						.meta("reason", "migration").log();
			}
			if (!closed.isEmpty()) {
				List<User> admins = users.findByRolesContainingAndActiveIsTrue(Role.ADMIN);
				notifications.noteInBell(admins, "moderation.entry.notice.title", "moderation.entry.notice.body",
						"/admin");
				for (User admin : admins) {
					notifications.mailAddress(admin.getEmail(), words.localeOf(admin), "moderation.entry.notice.title",
							"moderation.entry.notice.body");
				}
				log.info("AccountApprovalOnUpdate: new accounts now need approval ({})",
						closed.stream().map(AccountAdmission.OpenWay::way).toList());
			}
			markers.markDone(MARKER_ID, new Document("closed", closed.size()));
		}
		catch (RuntimeException ex) {
			log.warn("AccountApprovalOnUpdate failed; will retry on next start", ex);
		}
	}
}
