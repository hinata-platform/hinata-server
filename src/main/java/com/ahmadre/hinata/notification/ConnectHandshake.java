package com.ahmadre.hinata.notification;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A pending browser-mediated "Jetzt verbinden" handshake this server started and
 * is waiting on. A single document: the server holds the raw PKCE {@code verifier}
 * locally and polls the gateway with it until an operator approves in the portal,
 * at which point the minted credentials are collected over the back channel and
 * promoted to a {@link ConnectEnrollment}.
 *
 * <p>Persisted (rather than kept only in memory) so a short restart mid-handshake
 * resumes polling instead of silently dropping the flow. Removed as soon as the
 * handshake is enrolled, denied, expired, or cancelled.
 */
@Data
@Document("connect_handshake")
public class ConnectHandshake {

    public static final String SINGLETON_ID = "default";

    @Id
    private String id = SINGLETON_ID;

    /** Opaque id the operator approves in the portal (also in the /enroll URL). */
    private String handshakeId;

    /** Raw PKCE verifier — presented to the gateway to collect the secret. Never leaves this server except to the gateway. */
    private String verifier;

    /** The portal URL to open (built by the gateway from its configured portal base). */
    private String portalUrl;

    /** Epoch millis after which the handshake is dead. */
    private long expiresAt;

    private long startedAt;
}
