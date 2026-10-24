package com.ahmadre.hinata.common;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * A value read again from its source once the copy is older than its age (HIN-26). For what many
 * requests read and somebody seldom changes, such as the settings: a change tells only the
 * instance that made it, which hands the new value over ({@link #set}) or drops the copy
 * ({@link #clear}); another instance sees it at most that age late.
 */
public final class Expiring<T> {

	private final Duration maxAge;
	private final Supplier<T> source;
	private volatile Copy<T> copy;

	private record Copy<T>(T value, Instant readAt) {
	}

	public Expiring(Duration maxAge, Supplier<T> source) {
		this.maxAge = maxAge;
		this.source = source;
	}

	/** The value as read at most the age before [now]; read from the source again when older. */
	public T get(Instant now) {
		Copy<T> current = copy;
		if (current == null || !current.readAt().plus(maxAge).isAfter(now)) {
			current = new Copy<>(source.get(), now);
			copy = current;
		}
		return current.value();
	}

	/** Takes [value] as read at [now], as the instance that changed it hands it over. */
	public void set(T value, Instant now) {
		copy = new Copy<>(value, now);
	}

	/** Reads the source again on the next {@link #get}. */
	public void clear() {
		copy = null;
	}
}
