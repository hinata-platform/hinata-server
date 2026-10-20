package com.ahmadre.hinata.shifts;

import com.mongodb.MongoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs a piece of the module's writing in one Mongo transaction and starts it again when the
 * database says it collided with another one.
 *
 * <p>Two people taking the last place, or one person signing up for two shifts at once, are two
 * transactions writing the same document. MongoDB does not queue the second; it aborts it with a
 * {@code WriteConflict} labelled {@code TransientTransactionError} and expects the client to try
 * again. The server had no helper for that (it had two {@code @Transactional} methods), so here is
 * one: at most {@link #MAX_ATTEMPTS} attempts with a short random pause between them, and
 * {@code error.shifts.busy} when all of them collided.
 *
 * <p>Whatever the work publishes through the application event publisher is bound to the
 * transaction: listeners with {@code phase = AFTER_COMMIT} see only the attempt that committed, so
 * a retried attempt never delivers an audit record or a notification twice, and a rolled-back one
 * delivers nothing.
 *
 * <p>Inside a transaction that is already running the work simply joins it. Only the outermost
 * call retries, because a conflict aborts the whole transaction and only its owner can start it
 * again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftTx {

	/** Attempts in total, the first included. */
	public static final int MAX_ATTEMPTS = 3;

	/** MongoDB's code for a write conflict between two transactions. */
	static final int WRITE_CONFLICT = 112;

	/** Present only with transactions on; the module is off without them (HIN-49). */
	private final ObjectProvider<PlatformTransactionManager> managers;
	private final ShiftSettings settings;

	public <T> T run(Supplier<T> work) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return work.get();
		}
		return attempt(work, TransactionDefinition.PROPAGATION_REQUIRED);
	}

	/**
	 * As {@link #run}, but always in a transaction of its own, retried on its own. For work started
	 * from an after-commit listener (HIN-162: moving people up from a waiting list): the committed
	 * transaction's resources are still bound there, and joining them would write outside any
	 * transaction. Whatever the work publishes is delivered after its own commit.
	 */
	public <T> T runAlone(Supplier<T> work) {
		return attempt(work, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	private <T> T attempt(Supplier<T> work, int propagation) {
		PlatformTransactionManager manager = managers.getIfAvailable();
		if (manager == null) {
			// Unreachable while the module is on: ShiftSettings keeps it off without transactions.
			settings.requireEnabled();
			throw new IllegalStateException("shift planning needs a transaction manager");
		}
		TransactionTemplate template = new TransactionTemplate(manager);
		template.setPropagationBehavior(propagation);
		for (int attempt = 1; ; attempt++) {
			try {
				return template.execute(status -> work.get());
			}
			catch (RuntimeException failure) {
				if (!transientConflict(failure)) {
					throw failure;
				}
				if (attempt >= MAX_ATTEMPTS) {
					log.info("[shifts] gave up after {} conflicting attempts", attempt);
					throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.busy");
				}
				pause(attempt);
			}
		}
	}

	/**
	 * Whether transactions are there at all. Without them the module is off; the erasure listeners
	 * still clear what an earlier configuration left behind, with plain writes.
	 */
	public boolean available() {
		return managers.getIfAvailable() != null;
	}

	public void run(Runnable work) {
		run(() -> {
			work.run();
			return null;
		});
	}

	/** A write conflict, or anything the driver labels as worth running the transaction again for. */
	static boolean transientConflict(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof MongoException mongo
					&& (mongo.getCode() == WRITE_CONFLICT
							|| mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL))) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}

	/** A few milliseconds, growing and jittered, so the colliding attempts do not meet again. */
	private static void pause(int attempt) {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * attempt));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.busy");
		}
	}
}
