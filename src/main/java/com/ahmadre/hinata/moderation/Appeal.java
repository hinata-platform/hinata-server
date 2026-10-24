package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.RestoreOutcome;

import java.time.Instant;

/**
 * The person responsible objecting to a decision (HIN-171): in the app, or by a reply to the mail
 * that moderation enters for them. Decided by somebody other than who decided the case.
 *
 * @param note     why the appeal was decided as it was, at least twenty characters
 * @param restored what putting the content back came to, for an appeal that reversed a removal
 */
public record Appeal(State state, String text, Instant filedAt, Via via, String decidedBy, Instant decidedAt,
		String note, RestoreOutcome restored) {

	public enum State {
		PENDING,
		UPHELD,
		REVERSED
	}

	/** How the appeal came in. */
	public enum Via {
		APP,
		REPLY
	}

	static Appeal filed(String text, Instant filedAt, Via via) {
		return new Appeal(State.PENDING, text, filedAt, via, null, null, null, null);
	}
}
