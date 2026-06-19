package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * First-run setup: creates the organization and the initial administrator.
 * Can be completed from the app (Rocket.Chat-style wizard) or automatically
 * on boot when HINATA_SETUP_* environment variables are provided.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

	private final SettingsService settings;
	private final UserService userService;
	private final HinataProperties properties;

	public record SetupRequest(String organizationName, String adminEmail, String adminUsername,
			String adminDisplayName, String adminPassword) {
	}

	public boolean isCompleted() {
		return settings.get().isSetupCompleted();
	}

	public synchronized User complete(SetupRequest request) {
		ServerSettings current = settings.get();
		if (current.isSetupCompleted()) {
			throw ApiException.conflict("error.setup.alreadyCompleted");
		}
		User admin = userService.createLocal(request.adminEmail(), request.adminUsername(),
				request.adminDisplayName(), request.adminPassword(), Set.of(Role.ADMIN, Role.MEMBER));
		current.setOrganizationName(request.organizationName());
		current.setSetupCompleted(true);
		settings.save(current);
		log.info("Server setup completed for organization '{}'", request.organizationName());
		return admin;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void autoCompleteFromEnvironment() {
		HinataProperties.Setup env = properties.getSetup();
		if (!env.isAutoComplete() || isCompleted()) {
			return;
		}
		if (env.getOrganizationName() == null || env.getAdminEmail() == null
				|| env.getAdminUsername() == null || env.getAdminPassword() == null) {
			log.warn("hinata.setup.auto-complete=true but setup values are incomplete; skipping");
			return;
		}
		complete(new SetupRequest(env.getOrganizationName(), env.getAdminEmail(),
				env.getAdminUsername(),
				env.getAdminDisplayName() != null ? env.getAdminDisplayName() : env.getAdminUsername(),
				env.getAdminPassword()));
	}
}
