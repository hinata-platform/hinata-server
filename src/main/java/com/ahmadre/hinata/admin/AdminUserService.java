package com.ahmadre.hinata.admin;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.me.AccountMailService;
import com.ahmadre.hinata.me.RefreshSession;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Business logic for the admin user-management board: a paginated directory and
 * the full account lifecycle (invite · resend · activate/deactivate · promote/
 * revoke admin · password reset · revoke sessions · edit · delete). Every
 * destructive path is guarded by the last-active-admin and self-action
 * invariants the UI also enforces, so the server is authoritative.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long INVITE_TTL_DAYS = 7;
	private static final long RESET_TTL_MINUTES = 30;

	private final UserRepository users;
	private final UserService userService;
	private final SessionService sessions;
	private final NotificationService notifications;
	private final AdminMailService adminMail;
	private final AccountMailService accountMail;
	private final CurrentUser currentUser;
	private final PasswordEncoder passwordEncoder;
	private final HinataProperties properties;

	// --- Read: filter + sort + paginate --------------------------------------

	public AdminUserListResponse list(String query, String role, String status,
			String origin, String sortKey, String dir, int page, int perPage) {
		String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<User> all = users.findAll();

		List<User> filtered = all.stream().filter(u -> {
			if (role != null && !role.isBlank() && !role.equalsIgnoreCase(effectiveRole(u))) return false;
			if (status != null && !status.isBlank() && !status.equalsIgnoreCase(status(u))) return false;
			if (origin != null && !origin.isBlank()
					&& !origin.equalsIgnoreCase(u.getOrigin().name())) return false;
			if (!term.isEmpty()) {
				return contains(u.getDisplayName(), term) || contains(u.getEmail(), term)
						|| contains(u.getTitle(), term) || contains(u.getUsername(), term);
			}
			return true;
		}).sorted(comparator(sortKey, dir)).toList();

		long total = filtered.size();
		int pp = Math.max(1, perPage);
		int pages = Math.max(1, (int) Math.ceil((double) total / pp));
		int current = Math.min(Math.max(1, page), pages);
		int from = (current - 1) * pp;
		int to = (int) Math.min(from + (long) pp, total);
		List<AdminUserResponse> items = (from >= total ? List.<User>of() : filtered.subList(from, to))
				.stream().map(this::toResponse).toList();
		return new AdminUserListResponse(items, total, current, pp, counts(all));
	}

	/** Global KPI tallies across every user (independent of the active filters). */
	private AdminUserListResponse.Counts counts(List<User> all) {
		long total = all.size();
		long admins = all.stream().filter(User::isAdmin).count();
		long active = all.stream().filter(u -> u.isActive() && !u.isInvitePending()).count();
		long invited = all.stream().filter(User::isInvitePending).count();
		long expired = all.stream().filter(this::inviteExpired).count();
		long disabled = all.stream().filter(u -> !u.isActive() && !u.isInvitePending()).count();
		long activeAdmins = all.stream()
				.filter(u -> u.isAdmin() && u.isActive() && !u.isInvitePending()).count();
		return new AdminUserListResponse.Counts(total, admins, active, invited, expired,
				disabled, activeAdmins);
	}

	private boolean inviteExpired(User u) {
		return u.isInvitePending() && u.getInviteExpiresAt() != null
				&& u.getInviteExpiresAt().isBefore(Instant.now());
	}

	private Comparator<User> comparator(String key, String dir) {
		Comparator<User> cmp = switch (key == null ? "lastActive" : key) {
			case "name" -> Comparator.comparing(u -> safe(u.getDisplayName()));
			case "role" -> Comparator.comparing(this::effectiveRole);
			case "origin" -> Comparator.comparing(u -> u.getOrigin().name());
			case "status" -> Comparator.comparing(this::status);
			case "joinedAt" -> Comparator.comparing(u -> orEpoch(u.getJoinedAt()));
			default -> Comparator.comparing(u -> orEpoch(lastActiveOf(u)));
		};
		return "asc".equalsIgnoreCase(dir) ? cmp : cmp.reversed();
	}

	// --- Lifecycle: invite ---------------------------------------------------

	public int invite(List<String> emails, boolean admin, String message) {
		Set<Role> roles = admin ? Set.of(Role.ADMIN, Role.MEMBER) : Set.of(Role.MEMBER);
		String inviterId = currentUser.requireId();
		String inviterName = users.findById(inviterId).map(User::getDisplayName).orElse("Hinata");
		int sent = 0;
		for (String raw : emails) {
			String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
			if (email.isEmpty() || users.existsByEmailIgnoreCase(email)) continue;
			String secret = randomToken();
			Instant now = Instant.now();
			User invited = userService.createInvited(email, deriveName(email), roles, inviterId,
					passwordEncoder.encode(secret), now, now.plus(INVITE_TTL_DAYS, ChronoUnit.DAYS));
			adminMail.sendInvite(invited, inviteUrl(invited.getId(), secret), message, inviterName);
			sent++;
		}
		return sent;
	}

	public void resend(List<String> ids) {
		for (String id : ids) {
			User u = userService.get(id);
			if (!u.isInvitePending()) continue;
			String secret = randomToken();
			u.setInviteTokenHash(passwordEncoder.encode(secret));
			u.setInviteExpiresAt(Instant.now().plus(INVITE_TTL_DAYS, ChronoUnit.DAYS));
			u.setInvitedAt(Instant.now());
			u.setInvitedBy(currentUser.requireId());
			users.save(u);
			String inviterName = users.findById(currentUser.requireId())
					.map(User::getDisplayName).orElse("Hinata");
			adminMail.sendInvite(u, inviteUrl(u.getId(), secret), null, inviterName);
		}
	}

	// --- Lifecycle: status / role --------------------------------------------

	public void setStatus(List<String> ids, String status) {
		boolean activate = "ACTIVE".equalsIgnoreCase(status);
		for (String id : ids) {
			User u = userService.get(id);
			if (u.isInvitePending()) continue; // invites have no active/disabled state
			boolean wasActive = u.isActive();
			if (!activate) {
				if (u.getId().equals(currentUser.requireId())) {
					throw ApiException.badRequest("error.user.cannotDeactivateSelf");
				}
				if (u.isAdmin()) requireAnotherActiveAdmin(u, "error.user.cannotDeactivateLastAdmin");
				sessions.revokeAll(u.getId()); // deactivation forces sign-out everywhere
			}
			u.setActive(activate);
			User saved = users.save(u);
			if (saved.isActive() != wasActive) {
				if (saved.isActive()) notifications.notifyAccountActivated(saved);
				else notifications.notifyAccountDeactivated(saved);
			}
		}
	}

	public void setRole(List<String> ids, String role) {
		boolean admin = "ADMIN".equalsIgnoreCase(role);
		for (String id : ids) {
			User u = userService.get(id);
			boolean wasAdmin = u.isAdmin();
			if (wasAdmin == admin) continue;
			if (!admin) requireAnotherActiveAdmin(u, "error.user.cannotRemoveLastAdmin");
			u.setRoles(admin ? Set.of(Role.ADMIN, Role.MEMBER) : Set.of(Role.MEMBER));
			User saved = users.save(u);
			notifications.notifyRolesChanged(saved);
		}
	}

	// --- Lifecycle: credentials & sessions -----------------------------------

	public void sendPasswordReset(List<String> ids) {
		for (String id : ids) {
			User u = userService.get(id);
			if (u.isSso()) throw ApiException.badRequest("error.me.passwordManagedByProvider");
			if (u.isInvitePending()) continue;
			String secret = randomToken();
			u.setPasswordResetTokenHash(passwordEncoder.encode(secret));
			u.setPasswordResetExpiresAt(Instant.now().plus(RESET_TTL_MINUTES, ChronoUnit.MINUTES));
			users.save(u);
			String resetUrl = properties.getBaseUrl()
					+ "/api/v1/me/password-reset/confirm?token=" + u.getId() + "." + secret;
			accountMail.sendPasswordReset(u, resetUrl);
		}
	}

	public void revokeSessions(List<String> ids) {
		for (String id : ids) {
			sessions.revokeAll(userService.get(id).getId());
		}
	}

	// --- Edit details / delete ----------------------------------------------

	public AdminUserResponse updateDetails(String id, String displayName, String title, String email) {
		User u = userService.get(id);
		if (displayName != null) u.setDisplayName(displayName.trim());
		if (title != null) u.setTitle(title.trim());
		if (email != null && !email.equalsIgnoreCase(u.getEmail())) {
			if (u.isSso()) throw ApiException.badRequest("error.user.emailManagedByProvider");
			String normalized = email.trim().toLowerCase(Locale.ROOT);
			if (users.existsByEmailIgnoreCase(normalized)) {
				throw ApiException.conflict("error.user.emailInUse");
			}
			u.setEmail(normalized);
		}
		return toResponse(users.save(u));
	}

	public void delete(List<String> ids) {
		for (String id : ids) {
			User u = userService.get(id);
			if (u.getId().equals(currentUser.requireId())) {
				throw ApiException.badRequest("error.user.cannotDeleteSelf");
			}
			if (u.isAdmin()) requireAnotherActiveAdmin(u, "error.user.cannotDeleteLastAdmin");
			notifications.notifyAccountDeleted(u);
			userService.delete(u);
		}
	}

	// --- Mapping / helpers ---------------------------------------------------

	public AdminUserResponse toResponse(User u) {
		List<RefreshSession> ss = sessions.list(u.getId());
		return new AdminUserResponse(u.getId(), u.getDisplayName(), u.getUsername(), u.getEmail(),
				u.getTitle(), effectiveRole(u), u.getOrigin().name(), status(u), u.isTotpEnabled(),
				u.isSso(), ss.size(), lastActiveOf(ss), u.getInvitedAt(), u.getInvitedBy(),
				u.getJoinedAt());
	}

	private String effectiveRole(User u) {
		return u.isAdmin() ? "ADMIN" : "USER";
	}

	private String status(User u) {
		if (u.isInvitePending()) return "INVITED";
		return u.isActive() ? "ACTIVE" : "DISABLED";
	}

	private Instant lastActiveOf(User u) {
		return lastActiveOf(sessions.list(u.getId()));
	}

	private Instant lastActiveOf(List<RefreshSession> ss) {
		return ss.stream().map(RefreshSession::getLastActiveAt)
				.filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
	}

	private void requireAnotherActiveAdmin(User user, String messageKey) {
		if (users.countByRolesContainingAndActiveIsTrueAndIdNot(Role.ADMIN, user.getId()) == 0) {
			throw ApiException.conflict(messageKey);
		}
	}

	private String inviteUrl(String userId, String secret) {
		return properties.getBaseUrl() + "/api/v1/auth/invite/confirm?token=" + userId + "." + secret;
	}

	/** Title-cases the local part of an email as a friendly placeholder name. */
	static String deriveName(String email) {
		String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
		String[] parts = local.split("[._-]+");
		StringBuilder out = new StringBuilder();
		for (String p : parts) {
			if (p.isEmpty()) continue;
			if (out.length() > 0) out.append(' ');
			out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
		}
		return out.length() == 0 ? local : out.toString();
	}

	private static String randomToken() {
		byte[] buf = new byte[32];
		RANDOM.nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	private static boolean contains(String haystack, String needle) {
		return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
	}

	private static String safe(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}

	private static Instant orEpoch(Instant t) {
		return t == null ? Instant.EPOCH : t;
	}
}
