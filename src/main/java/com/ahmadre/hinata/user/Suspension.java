package com.ahmadre.hinata.user;

import java.time.Instant;

/**
 * A suspension (HIN-172): a deactivation with a reason and an end, always the effect of a
 * moderation case. Without primitives, so an account stored before it existed still reads.
 *
 * @param until   when it ends by itself; null for until further notice
 * @param caseId  the case that decided it, whose statement gives the reasons
 * @param by      who decided it
 * @param at      since when
 */
public record Suspension(Instant until, String caseId, String by, Instant at) {
}
