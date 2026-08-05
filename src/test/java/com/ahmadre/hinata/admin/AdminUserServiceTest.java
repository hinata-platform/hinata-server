package com.ahmadre.hinata.admin;

import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.me.AccountMailService;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceTest {

	private UserRepository users;
	private UserService userService;
	private SessionService sessions;
	private NotificationService notifications;
	private AdminMailService adminMail;
	private AccountMailService accountMail;
	private CurrentUser currentUser;
	private AdminUserService service;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		userService = mock(UserService.class);
		sessions = mock(SessionService.class);
		notifications = mock(NotificationService.class);
		GatewayService gateway = mock(GatewayService.class);
		when(gateway.relayLink(anyString(), anyString())).thenReturn("https://connect.example/l/x");
		adminMail = mock(AdminMailService.class);
		accountMail = mock(AccountMailService.class);
		currentUser = mock(CurrentUser.class);
		when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
		when(sessions.list(anyString())).thenReturn(List.of());
		when(currentUser.requireId()).thenReturn("me");
		// AuditService exposes a fluent builder (event(...).actor(...).target(...).log());
		// RETURNS_SELF makes the mocked Entry return itself for every chained call.
		AuditService audit = mock(AuditService.class);
		when(audit.event(any())).thenReturn(mock(AuditService.Entry.class, RETURNS_SELF));
		service = new AdminUserService(users, userService, sessions, notifications, gateway, adminMail,
				accountMail, currentUser, new BCryptPasswordEncoder(4), audit,
				mock(ModerationService.class));
	}

	private User user(String id, Role role, boolean active, User.Origin origin) {
		Set<Role> roles = role == Role.ADMIN ? Set.of(Role.ADMIN, Role.MEMBER) : Set.of(Role.MEMBER);
		return User.builder().id(id).email(id + "@x.de").username(id).displayName(id)
				.roles(roles).origin(origin).active(active).build();
	}

	@Test
	void revokingLastActiveAdminIsRejected() {
		User admin = user("a1", Role.ADMIN, true, User.Origin.LOCAL);
		when(userService.get("a1")).thenReturn(admin);
		when(users.countByRolesContainingAndActiveIsTrueAndIdNot(Role.ADMIN, "a1")).thenReturn(0L);

		assertThatThrownBy(() -> service.setRole(List.of("a1"), "USER"))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("cannotRemoveLastAdmin");
	}

	@Test
	void passwordResetRejectedForSsoUsers() {
		when(userService.get("s1")).thenReturn(user("s1", Role.MEMBER, true, User.Origin.OIDC));

		assertThatThrownBy(() -> service.sendPasswordReset(List.of("s1")))
				.isInstanceOf(ApiException.class);
		verify(accountMail, never()).sendPasswordReset(any(), anyString());
	}

	@Test
	void deactivationRevokesSessions() {
		User u = user("u1", Role.MEMBER, true, User.Origin.LOCAL);
		when(userService.get("u1")).thenReturn(u);

		service.setStatus(List.of("u1"), "DISABLED");

		assertThat(u.isActive()).isFalse();
		verify(sessions).revokeAll("u1");
		verify(notifications).notifyAccountDeactivated(u);
	}

	@Test
	void inviteSkipsActiveMembersAndMailsNewOnes() {
		// An address that already belongs to an active member is skipped; a new
		// address is created and mailed.
		when(users.findByEmailIgnoreCase("taken@x.de"))
				.thenReturn(Optional.of(user("taken", Role.MEMBER, true, User.Origin.LOCAL)));
		when(users.findByEmailIgnoreCase("fresh@x.de")).thenReturn(Optional.empty());
		when(users.findById("me")).thenReturn(Optional.of(user("me", Role.ADMIN, true, User.Origin.LOCAL)));
		when(userService.createInvited(anyString(), anyString(), any(), anyString(), anyString(),
				any(), any())).thenReturn(user("new", Role.MEMBER, false, User.Origin.LOCAL));
		when(adminMail.sendInvite(any(), anyString(), any(), anyString())).thenReturn(true);

		AdminUserService.InviteResult r = service.invite(List.of("taken@x.de", "fresh@x.de"), false, "hi");

		assertThat(r.sent()).isEqualTo(1);
		assertThat(r.skipped()).isEqualTo(1);
		verify(adminMail).sendInvite(any(), anyString(), any(), anyString());
	}
}
