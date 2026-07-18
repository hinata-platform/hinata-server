package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adminbereich → Connect: enrols this instance with Hinata Connect using a
 * one-time token from the Connect portal, shows the enrolment/verification
 * state, and can drop the local enrolment. The enrolment token is used once and
 * never stored; only the issued per-instance credentials are persisted.
 */
@Tag(name = "Admin · Connect")
@RestController
@RequestMapping("/api/v1/admin/connect")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ConnectAdminController {

	private final GatewayService gateway;
	private final AuditService audit;
	private final CurrentUser currentUser;

	@GetMapping
	public GatewayService.ConnectStatus status() {
		// Refresh opportunistically so the admin sees live verification state.
		return gateway.refreshStatus();
	}

	public record EnrollRequest(@NotBlank @Size(max = 256) String token) {
	}

	@PostMapping("/enroll")
	public GatewayService.ConnectStatus enroll(@Valid @RequestBody EnrollRequest req) {
		try {
			GatewayService.ConnectStatus status = gateway.enroll(req.token());
			audit.event(AuditAction.CONNECT_ENROLLED)
					.actor(currentUser.require())
					.meta("serverId", status.serverId())
					.log();
			return status;
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enrollment rejected");
		} catch (IllegalStateException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "gateway unavailable");
		}
	}

	@DeleteMapping
	public GatewayService.ConnectStatus disconnect() {
		String serverId = gateway.status().serverId();
		gateway.disconnect();
		audit.event(AuditAction.CONNECT_DISCONNECTED)
				.actor(currentUser.require())
				.meta("serverId", serverId == null ? "-" : serverId)
				.log();
		return gateway.status();
	}

	/** What the app needs to open the operator portal for the automated flow. */
	public record HandshakeStartResponse(String portalUrl, long expiresAt) {
	}

	/**
	 * Starts the automated "Jetzt verbinden" flow: returns the portal URL the
	 * admin opens to approve enrolment. The server then collects the credentials
	 * over the back channel; the app watches {@code GET /connect} until enrolled.
	 */
	@PostMapping("/handshake/start")
	public HandshakeStartResponse handshakeStart() {
		try {
			GatewayService.HandshakeStart started = gateway.startHandshake();
			audit.event(AuditAction.CONNECT_HANDSHAKE_STARTED)
					.actor(currentUser.require())
					.log();
			return new HandshakeStartResponse(started.portalUrl(), started.expiresAt());
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handshake rejected");
		} catch (IllegalStateException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "gateway unavailable");
		}
	}

	/** Cancels an in-flight handshake (admin abandoned the flow). */
	@DeleteMapping("/handshake")
	public GatewayService.ConnectStatus handshakeCancel() {
		return gateway.cancelHandshake();
	}
}
