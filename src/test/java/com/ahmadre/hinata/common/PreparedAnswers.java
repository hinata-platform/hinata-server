package com.ahmadre.hinata.common;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Prepared lookup answers in order, the last one repeated, and a count of the questions. */
public final class PreparedAnswers implements PublicDns.Resolver {

	private final Deque<InetAddress[]> queue = new ArrayDeque<>();
	private final AtomicInteger calls = new AtomicInteger();

	public PreparedAnswers(InetAddress[]... answers) {
		queue.addAll(List.of(answers));
	}

	@Override
	public synchronized InetAddress[] resolve(String host) {
		calls.incrementAndGet();
		return queue.size() > 1 ? queue.poll() : queue.peek();
	}

	public int calls() {
		return calls.get();
	}
}
