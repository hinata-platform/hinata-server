package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * How long the reads behind a board may take: each read at most {@link #MAX_READ_TIME}, and the reads of
 * one request together at most {@link #MAX_REQUEST_TIME}. A request runs its reads {@link #within} its
 * time, and every read takes {@link #left} as its limit, so a request of many reads cannot hold a thread
 * for as long as all of them together could take.
 */
final class BoardTime {

	/** How long one read may take before the database gives up on it. */
	static final Duration MAX_READ_TIME = Duration.ofSeconds(5);

	/** How long the reads of one request may take together. */
	static final Duration MAX_REQUEST_TIME = Duration.ofSeconds(10);

	/** When the reads of the request on this thread run out of time; unset outside a request's reads. */
	private static final ThreadLocal<Instant> DEADLINE = new ThreadLocal<>();

	private BoardTime() {
	}

	/** Runs [reads] within {@link #MAX_REQUEST_TIME}, or within the time of the request they are part of. */
	static <T> T within(Supplier<T> reads) {
		return within(MAX_REQUEST_TIME, reads);
	}

	/** Runs [reads] within [time], or within the time of the request they are part of. */
	static <T> T within(Duration time, Supplier<T> reads) {
		if (DEADLINE.get() != null) {
			return reads.get();
		}
		DEADLINE.set(Instant.now().plus(time));
		try {
			return reads.get();
		}
		finally {
			DEADLINE.remove();
		}
	}

	/**
	 * How long the next read may take: {@link #MAX_READ_TIME}, or what is left of its request's time when
	 * that is less.
	 *
	 * @throws ApiException 503 once the request has less than a millisecond left, which the database would
	 *                      read as no limit at all
	 */
	static Duration left() {
		Instant deadline = DEADLINE.get();
		if (deadline == null) {
			return MAX_READ_TIME;
		}
		Duration left = Duration.between(Instant.now(), deadline);
		if (left.toMillis() < 1) {
			throw busy();
		}
		return left.compareTo(MAX_READ_TIME) < 0 ? left : MAX_READ_TIME;
	}

	/** The answer to reads that ran out of time: the server is busy, which the app can explain. */
	static ApiException busy() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "error.board.busy");
	}
}
