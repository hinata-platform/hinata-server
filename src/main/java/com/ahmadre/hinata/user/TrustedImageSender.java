package com.ahmadre.hinata.user;

import java.time.Instant;

/**
 * A sender domain a person loads external images from without being asked (HIN-181), and when
 * they said so.
 *
 * @param domain  normalised by {@link SenderDomains#normalize}
 * @param addedAt when it was trusted
 */
public record TrustedImageSender(String domain, Instant addedAt) {
}
