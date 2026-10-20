package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The instance's room for rendering documents: at most {@link #MAX_RUNNING} time reports and shift
 * plans at once, shared between them (HIN-163).
 *
 * <p>A document export reads a cursor and lays out a file on a request thread, and a workbook or a
 * Word file sits in memory until it is written. The per-caller budget ({@link ExportRateLimiter})
 * keeps one account from spending everybody's time; this keeps a handful of accounts from spending
 * the heap. One semaphore for every kind of document, because they draw on the same memory.
 *
 * <p>Taken before the caller's budget: a busy instance is nobody's fault and costs the caller
 * nothing.
 */
@Component
public class ExportSlots {

	/** Documents rendered at once across the instance. */
	public static final int MAX_RUNNING = 4;

	private final Semaphore running = new Semaphore(MAX_RUNNING);

	/** One slot, given back once however often it is closed. */
	public static final class Slot implements AutoCloseable {

		private final Semaphore owner;
		private final AtomicBoolean released = new AtomicBoolean();

		private Slot(Semaphore owner) {
			this.owner = owner;
		}

		@Override
		public void close() {
			if (released.compareAndSet(false, true)) {
				owner.release();
			}
		}
	}

	/**
	 * Takes a slot without waiting.
	 *
	 * @throws ApiException 429 {@code error.rateLimited} when every slot is taken
	 */
	public Slot tryAcquire() {
		if (!running.tryAcquire()) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.rateLimited");
		}
		return new Slot(running);
	}
}
