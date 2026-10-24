package com.ahmadre.hinata.moderation;

import java.time.Instant;

/** The person responsible objecting to a decision (HIN-171), and how it was decided. */
public record Appeal(State state, String text, Instant filedAt, String decidedBy, Instant decidedAt) {

	public enum State {
		PENDING,
		UPHELD,
		REVERSED
	}
}
