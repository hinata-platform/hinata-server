package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Setup")
@RestController
@RequestMapping("/api/v1/setup")
@RequiredArgsConstructor
public class SetupController {

	private final SetupService setupService;
	private final SettingsService settings;

	public record SetupStatus(boolean setupCompleted, String organizationName) {
	}

	public record CompleteSetupRequest(
			@NotBlank @Size(max = 120) String organizationName,
			@NotBlank @Email String adminEmail,
			@NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,40}") String adminUsername,
			@NotBlank @Size(max = 120) String adminDisplayName,
			// Min length is enforced dynamically by UserService#validatePassword
			// (SecurityPolicy, admin-configurable); only the hard upper bound is static.
			@NotBlank @Size(max = 128) String adminPassword) {
	}

	@Operation(summary = "Setup status", description = "Returns whether first-run setup has been completed.")
	@SecurityRequirements
	@GetMapping("/status")
	public SetupStatus status() {
		ServerSettings current = settings.get();
		return new SetupStatus(current.isSetupCompleted(), current.getOrganizationName());
	}

	@Operation(summary = "Complete first-run setup", description = "Creates the organisation and first admin user. Only callable once — returns 409 if already completed.")
	@ApiResponse(responseCode = "201", description = "Setup completed")
	@ApiResponse(responseCode = "409", description = "Already completed")
	@SecurityRequirements
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, String> complete(@RequestBody @Valid CompleteSetupRequest request) {
		User admin = setupService.complete(new SetupService.SetupRequest(
				request.organizationName(), request.adminEmail(), request.adminUsername(),
				request.adminDisplayName(), request.adminPassword()));
		return Map.of("adminId", admin.getId());
	}
}
