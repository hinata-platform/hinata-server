package com.ahmadre.hinata.notification;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * This server's enrolment with Hinata Connect (the central push + universal-link
 * relay). A single document: the credentials issued when an admin enrolled the
 * instance with a one-time token from the Connect portal.
 *
 * <p>Persisting the credentials (instead of the old re-register-on-every-boot
 * flow) is what removes the shared bootstrap secret from the trust model: the
 * gateway hands out the per-instance secret exactly once, and this server keeps
 * it — the same way it keeps its other integration credentials.
 */
@Data
@Document("connect_enrollment")
public class ConnectEnrollment {

	public static final String SINGLETON_ID = "default";

	@Id
	private String id = SINGLETON_ID;

	/** Gateway base URL the enrolment was made against. */
	private String gatewayBaseUrl;

	private String serverId;
	private String secret;

	/** Nonce served at /.well-known/hinata-connect-challenge (domain proof). */
	private String challenge;

	/** Last state reported by the gateway (refreshed on status polls). */
	private boolean domainVerified;

	private String apiBaseUrl;
	private String webBaseUrl;

	private long enrolledAt;
}
