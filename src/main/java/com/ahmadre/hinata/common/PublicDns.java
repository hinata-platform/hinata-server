package com.ahmadre.hinata.common;

import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.io.IOException;
import java.io.Serial;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The way from a host name to a socket for a request to an address somebody else
 * chose: resolve once, check every answer, connect to exactly the checked addresses.
 *
 * <p>Checking a name and then letting the HTTP client resolve it again leaves a gap:
 * a rebinding DNS server answers the check with a public address and the connection
 * with an internal one. OkHttp's {@link Dns} hook closes that gap. The client asks this
 * class and nothing else, a host is refused if any answer is off the public internet
 * ({@link PublicAddresses}), and the client connects to the answers it was given. Two
 * ways around the hook remain, and the caller closes them:
 *
 * <ul>
 * <li>A host OkHttp reads as an address never reaches the hook. {@code 127.1},
 * {@code 2130706433}, {@code [::ffff:a9fe:a9fe]} and the root name {@code .} are
 * connected to directly. Check the host with {@link #looksLikeAddress} before the
 * call.</li>
 * <li>A proxy resolves the name itself, and a pooled connection skips the lookup.
 * Build the client with {@link #clientBuilder()}, which has neither.</li>
 * </ul>
 *
 * <p>OkHttp's call timeout cannot interrupt a JDK lookup, so lookups run on threads of
 * their own and are given up after the timeout, or sooner when the request's own time
 * runs out ({@link #within}). There is no queue, and at most {@value #MAX_LOOKUPS}
 * threads: a lookup that hangs on a silent name server keeps its thread until the
 * operating system gives up, and a queue behind a few of those would make every other
 * request wait for them.
 */
public final class PublicDns implements Dns {

	private static final int MAX_LOOKUPS = 32;

	/** OkHttp's own test for "this host is an address"; such a host never reaches the hook. */
	private static final Pattern ADDRESS_LITERAL = Pattern.compile("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)");

	/** Four decimal octets without leading zeros: the one IPv4 spelling every reader takes for the same address. */
	private static final Pattern DOTTED_QUAD =
			Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

	/** When the request on this thread has to be done, as a {@link System#nanoTime()} value; see {@link #within}. */
	private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

	private final Resolver resolver;
	private final Duration timeout;
	private final ThreadPoolExecutor lookups;

	/**
	 * @param name     the name of the lookup threads
	 * @param resolver how names become addresses, {@link Resolver#SYSTEM} outside tests
	 * @param timeout  how long one lookup may take
	 */
	public PublicDns(String name, Resolver resolver, Duration timeout) {
		this.resolver = resolver;
		this.timeout = timeout;
		this.lookups = new ThreadPoolExecutor(0, MAX_LOOKUPS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
				Thread.ofPlatform().name(name + "-", 1).daemon().factory());
	}

	/**
	 * Runs [attempt] with every lookup it causes on this thread given up at [deadline], a
	 * {@link System#nanoTime()} value. OkHttp resolves on the thread that executes a call,
	 * so wrapping {@code call.execute()} is enough.
	 */
	public <T> T within(long deadline, Attempt<T> attempt) throws IOException {
		Long outer = DEADLINE.get();
		DEADLINE.set(deadline);
		try {
			return attempt.run();
		}
		finally {
			if (outer == null) {
				DEADLINE.remove();
			}
			else {
				DEADLINE.set(outer);
			}
		}
	}

	/**
	 * The addresses of [host], every one of them public.
	 *
	 * @throws SlowLookup           when the lookup takes longer than it may, or
	 *                              {@value #MAX_LOOKUPS} lookups are still running
	 * @throws NotPublic            when any answer is off the public internet
	 * @throws UnknownHostException when the name has no address
	 */
	@Override
	public List<InetAddress> lookup(String host) throws UnknownHostException {
		Long deadline = DEADLINE.get();
		long wait = deadline == null ? timeout.toNanos() : Math.min(timeout.toNanos(), deadline - System.nanoTime());
		if (wait <= 0) {
			throw new SlowLookup();
		}
		Future<InetAddress[]> answer;
		try {
			answer = lookups.submit(() -> resolver.resolve(host));
		}
		catch (RejectedExecutionException ex) {
			throw new SlowLookup();
		}
		InetAddress[] addresses;
		try {
			addresses = answer.get(wait, TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			answer.cancel(true);
			throw new SlowLookup();
		}
		catch (InterruptedException ex) {
			answer.cancel(true);
			Thread.currentThread().interrupt();
			throw new SlowLookup();
		}
		catch (ExecutionException ex) {
			throw new UnknownHostException();
		}
		if (addresses == null || addresses.length == 0) {
			throw new UnknownHostException();
		}
		for (InetAddress address : addresses) {
			if (!PublicAddresses.isPublic(address)) {
				throw new NotPublic();
			}
		}
		return List.of(addresses);
	}

	/**
	 * A client that reaches hosts through this lookup and no other way: no proxy, no
	 * connection kept for reuse, and no redirects or retries, which are the caller's to
	 * check. HTTP/1.1 only, whose response headers OkHttp caps at 256 KB; HTTP/2 header
	 * frames have no such cap here. Timeouts are left to the caller.
	 */
	public OkHttpClient.Builder clientBuilder() {
		return new OkHttpClient.Builder()
				.dns(this)
				.proxy(Proxy.NO_PROXY)
				.protocols(List.of(Protocol.HTTP_1_1))
				.followRedirects(false)
				.followSslRedirects(false)
				.retryOnConnectionFailure(false)
				.connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS));
	}

	/** Stops the idle lookup threads; one that hangs ends with its lookup. */
	public void shutdown() {
		lookups.shutdownNow();
	}

	/** Whether OkHttp takes [host] for an address and connects to it without asking the hook. */
	public static boolean looksLikeAddress(String host) {
		return ADDRESS_LITERAL.matcher(host).matches();
	}

	/**
	 * The address [host] spells, when it is written plainly: four decimal octets, or IPv6
	 * as OkHttp writes a host. Empty for a name and for every other spelling, such as
	 * {@code 127.1}, {@code 2130706433} or {@code .}, which readers disagree about.
	 * Nothing is resolved.
	 */
	public static Optional<InetAddress> plainAddress(String host) {
		boolean ipv4 = DOTTED_QUAD.matcher(host).matches();
		boolean ipv6 = host.indexOf(':') >= 0 && looksLikeAddress(host);
		if (!ipv4 && !ipv6) {
			return Optional.empty();
		}
		try {
			// Both forms start with a digit or a colon, which the JDK parses as a literal
			// and never sends to a name server.
			return Optional.of(InetAddress.getByName(host));
		}
		catch (UnknownHostException ex) {
			return Optional.empty();
		}
	}

	/** How host names become addresses. */
	@FunctionalInterface
	public interface Resolver {

		/** The JDK's resolver. */
		Resolver SYSTEM = InetAddress::getAllByName;

		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	/** Work that causes lookups, such as {@code call.execute()}. */
	@FunctionalInterface
	public interface Attempt<T> {
		T run() throws IOException;
	}

	/** The lookup took longer than it may, or could not start because too many are still running. */
	public static final class SlowLookup extends UnknownHostException {
		@Serial
		private static final long serialVersionUID = 1L;
	}

	/** The name has an address off the public internet. */
	public static final class NotPublic extends UnknownHostException {
		@Serial
		private static final long serialVersionUID = 1L;
	}
}
