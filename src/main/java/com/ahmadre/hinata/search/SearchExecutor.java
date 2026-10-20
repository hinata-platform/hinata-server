package com.ahmadre.hinata.search;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Where the categories of one search run: side by side, so a slow category costs its own
 * time and not the sum of all of them. Virtual threads, because every task waits on
 * MongoDB and holds no lock.
 *
 * <p>A class of its own so a test can run the categories on the calling thread and count
 * the queries one category sends.
 */
@Component
public class SearchExecutor {

	private final Executor delegate;

	public SearchExecutor() {
		this(Executors.newVirtualThreadPerTaskExecutor());
	}

	public SearchExecutor(Executor delegate) {
		this.delegate = delegate;
	}

	<T> CompletableFuture<T> supply(Supplier<T> task) {
		return CompletableFuture.supplyAsync(task, delegate);
	}
}
