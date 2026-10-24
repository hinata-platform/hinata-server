package com.ahmadre.hinata.user;

/**
 * Every session of [userId] ended at once (HIN-172): deactivated, suspended, signed out
 * everywhere. Whoever keeps a stream open for the person closes it, so nothing more reaches a
 * device that is signed out.
 */
public record SessionsRevokedEvent(String userId) {
}
