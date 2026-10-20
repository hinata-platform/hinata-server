package com.ahmadre.hinata.search;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Where the categories of one search run: side by side, so a slow category costs its own
 * time and not the sum of all of them. Virtual threads, because every task waits on
 * MongoDB and holds no lock.
 *
 * <p>One search runs at most {@link #PER_SEARCH} of its parts at once (HIN-165), each search
 * on its own {@link Lane}: a search holds at most that many connections of the shared pool,
 * however many categories it asks, and a part still waiting for its turn when the search's
 * time is up is never sent.
 *
 * <p>A class of its own so a test can run the categories on the calling thread and count
 * the queries one category sends.
 */
@Component
public class SearchExecutor {

	/** How many parts of one search run at the same time. */
	static final int PER_SEARCH = 6;

	private final Executor delegate;

	public SearchExecutor() {
		this(Executors.newVirtualThreadPerTaskExecutor());
	}

	public SearchExecutor(Executor delegate) {
		this.delegate = delegate;
	}

	/** A lane for the parts of the search [context] belongs to. */
	Lane lane(SearchContext context) {
		return new Lane(context);
	}

	/** The parts of one search, at most {@link #PER_SEARCH} at once, none after its time. */
	final class Lane {

		private final Semaphore turns = new Semaphore(PER_SEARCH);
		private final SearchContext context;

		private Lane(SearchContext context) {
			this.context = context;
		}

		<T> CompletableFuture<T> supply(Supplier<T> task) {
			return CompletableFuture.supplyAsync(() -> {
				if (!acquire()) throw new SearchContext.OutOfTime();
				try {
					return task.get();
				}
				finally {
					turns.release();
				}
			}, delegate);
		}

		private boolean acquire() {
			try {
				return turns.tryAcquire(Math.max(0, context.nanosLeft()), TimeUnit.NANOSECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}
}
