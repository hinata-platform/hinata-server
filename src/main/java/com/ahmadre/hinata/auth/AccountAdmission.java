package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who comes in without anybody deciding (HIN-175): a self-registration while approval is off, and
 * the first sign-in through an SSO provider that lets in whoever it knows. An account made that
 * way reads what the organisation shares with everybody, and under the DSA (recital 14) that makes
 * the content public, with the duties of an online platform. The admin area shows every such way.
 */
@Component
@RequiredArgsConstructor
public class AccountAdmission {

	/** The ways in, by the name the settings and the provider registrations use. */
	public static final String REGISTRATION = "registration";
	public static final String OIDC = "oidc";
	public static final String OAUTH2 = "oauth2";
	public static final String SAML = "saml";
	public static final String LDAP = "ldap";

	private final AuthPolicy authPolicy;
	private final SettingsService settings;
	private final UserRepository users;
	private final NotificationService notifications;
	private final AuthMailService authMail;
	private final GatewayService gateway;

	/** A way in that lets people in without approval: [way] as above, [name] as the admin area shows it. */
	public record OpenWay(String way, String name) {
	}

	public List<OpenWay> openWays() {
		return openWays(settings.get());
	}

	/** The open ways of [stored], for settings as they are or as a save would make them. */
	public List<OpenWay> openWays(ServerSettings stored) {
		List<OpenWay> open = new ArrayList<>();
		ServerSettings.App app = stored.getApp();
		if (authPolicy.localAuthEnabled(app) && authPolicy.registrationEnabled(app)
				&& !authPolicy.requireAdminApproval(app)) {
			open.add(new OpenWay(REGISTRATION, null));
		}
		if (stored.getOidc() != null && stored.getOidc().isOpen()) {
			open.add(new OpenWay(OIDC, stored.getOidc().getDisplayName()));
		}
		if (stored.getOauth2() != null && stored.getOauth2().isOpen()) {
			open.add(new OpenWay(OAUTH2, stored.getOauth2().getDisplayName()));
		}
		if (stored.getSaml() != null && stored.getSaml().isOpen()) {
			open.add(new OpenWay(SAML, stored.getSaml().getDisplayName()));
		}
		if (stored.getLdap() != null && stored.getLdap().isOpen()) {
			open.add(new OpenWay(LDAP, "LDAP"));
		}
		return open;
	}

	/**
	 * Closes every way in of [stored] that would let people in without approval, enabled or not,
	 * and names those that were open: for an update and for a new instance (HIN-175).
	 */
	public List<OpenWay> requireApprovalEverywhere(ServerSettings stored) {
		List<OpenWay> wereOpen = openWays(stored);
		if (!authPolicy.requireAdminApproval(stored.getApp())) {
			stored.getApp().setRequireAdminApproval(true);
		}
		for (ServerSettings.ProviderAdmission provider : List.<ServerSettings.ProviderAdmission>of(stored.getOidc(),
				stored.getOauth2(), stored.getSaml(), stored.getLdap())) {
			if (!Boolean.TRUE.equals(provider.getRequireApproval()) && domains(provider).isEmpty()) {
				approvalOn(provider);
			}
		}
		return wereOpen;
	}

	/**
	 * Whether the first sign-in through [provider] with [email] has to wait for an administrator:
	 * when the provider asks for approval, or names domains and the address is in none of them. A
	 * provider this server does not know waits.
	 */
	public boolean firstSignInWaits(String provider, String email) {
		ServerSettings stored = settings.get();
		ServerSettings.ProviderAdmission admission = switch (provider == null ? "" : provider) {
			case OIDC -> stored.getOidc();
			case OAUTH2 -> stored.getOauth2();
			case SAML -> stored.getSaml();
			case LDAP -> stored.getLdap();
			default -> null;
		};
		if (admission == null || Boolean.TRUE.equals(admission.getRequireApproval())) {
			return true;
		}
		List<String> domains = domains(admission);
		return !domains.isEmpty() && !domains.contains(domainOf(email));
	}

	/**
	 * Tells every administrator that [newUser] waits to be let in: in the bell and by mail, with a
	 * link that opens the person in the native app.
	 */
	public void requestApproval(User newUser) {
		List<User> admins = users.findByRolesContainingAndActiveIsTrue(Role.ADMIN);
		notifications.notifyAdminsPendingApproval(admins, newUser);
		// The new user's id travels as the relay "token"; the app forwards it to
		// /admin/users?token=<id> and the board opens that user's detail drawer.
		String reviewUrl = gateway.relayLink("/admin/users", newUser.getId());
		for (User admin : admins) {
			authMail.sendApprovalRequest(admin, newUser, reviewUrl);
		}
	}

	private static List<String> domains(ServerSettings.ProviderAdmission provider) {
		return provider.getAllowedDomains() == null ? List.of() : provider.getAllowedDomains().stream()
				.filter(domain -> domain != null && !domain.isBlank())
				.map(domain -> domain.strip().toLowerCase(Locale.ROOT).replaceFirst("^@", "")).toList();
	}

	private static String domainOf(String email) {
		int at = email == null ? -1 : email.lastIndexOf('@');
		return at < 0 ? "" : email.substring(at + 1).strip().toLowerCase(Locale.ROOT);
	}

	private static void approvalOn(ServerSettings.ProviderAdmission provider) {
		switch (provider) {
			case ServerSettings.Oidc oidc -> oidc.setRequireApproval(true);
			case ServerSettings.OAuth2 oauth2 -> oauth2.setRequireApproval(true);
			case ServerSettings.Saml saml -> saml.setRequireApproval(true);
			case ServerSettings.Ldap ldap -> ldap.setRequireApproval(true);
			default -> throw new IllegalStateException("unknown provider " + provider);
		}
	}
}
