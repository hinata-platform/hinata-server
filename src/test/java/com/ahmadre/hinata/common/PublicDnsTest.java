package com.ahmadre.hinata.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lookup itself. How the fetchers connect through it is tested with them. */
class PublicDnsTest {

	private static final InetAddress PUBLIC = literal("93.184.215.14");
	private static final InetAddress PRIVATE = literal("10.0.0.7");

	private final List<PublicDns> lookups = new ArrayList<>();
	private final CountDownLatch released = new CountDownLatch(1);

	@AfterEach
	void stopLookups() {
		released.countDown();
		lookups.forEach(PublicDns::shutdown);
	}

	@Test
	void refusesANameWithAnyAnswerOffThePublicInternet() {
		PublicDns dns = dns(new PreparedAnswers(new InetAddress[] { PUBLIC, PRIVATE }), Duration.ofSeconds(1));

		assertThatThrownBy(() -> dns.lookup("images.test")).isInstanceOf(PublicDns.NotPublic.class);
	}

	@Test
	void aLookupThatHangsDoesNotHoldUpTheLookupsOfOthers() throws UnknownHostException {
		PublicDns dns = dns(hangingFor("slow"), Duration.ofMillis(200));
		for (int i = 0; i < 8; i++) {
			String slow = "slow" + i + ".test";
			assertThatThrownBy(() -> dns.lookup(slow)).isInstanceOf(PublicDns.SlowLookup.class);
		}

		// Eight lookups still hang, and the next one gets a thread of its own.
		assertThat(dns.lookup("images.test")).containsExactly(PUBLIC);
	}

	@Test
	void givesUpWhenTheRequestRunsOutOfTime() {
		PublicDns dns = dns(hangingFor("slow"), Duration.ofSeconds(10));
		long started = System.nanoTime();

		assertThatThrownBy(() -> dns.within(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200),
				() -> dns.lookup("slow.test"))).isInstanceOf(PublicDns.SlowLookup.class);

		// The lookup may take ten seconds, the request had a fifth of one.
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void knowsWhichHostsOkHttpConnectsToWithoutALookup() {
		assertThat(List.of("127.0.0.1", "127.1", "2130706433", ".", "::1", "64:ff9b::a9fe:a9fe"))
				.allMatch(PublicDns::looksLikeAddress);
		assertThat(List.of("images.test", "localhost", "0x7f.1", "1.example"))
				.noneMatch(PublicDns::looksLikeAddress);
	}

	@Test
	void readsAnAddressOnlyInItsPlainSpelling() {
		assertThat(PublicDns.plainAddress("93.184.215.14")).contains(PUBLIC);
		assertThat(PublicDns.plainAddress("::1")).contains(literal("::1"));
		for (String unclear : List.of("127.1", "2130706433", "0177.0.0.1", "01.2.3.4", "256.1.1.1", "1.2.3.4.5",
				".", "1:2", "images.test")) {
			assertThat(PublicDns.plainAddress(unclear)).as(unclear).isEmpty();
		}
	}

	private PublicDns dns(PublicDns.Resolver resolver, Duration timeout) {
		PublicDns dns = new PublicDns("test-lookup", resolver, timeout);
		lookups.add(dns);
		return dns;
	}

	/** Answers every host at once, except those starting with [prefix], which wait for the test to end. */
	private PublicDns.Resolver hangingFor(String prefix) {
		return host -> {
			if (host.startsWith(prefix)) {
				await(released);
			}
			return new InetAddress[] { PUBLIC };
		};
	}

	private static InetAddress literal(String address) {
		try {
			return InetAddress.getByName(address);
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
