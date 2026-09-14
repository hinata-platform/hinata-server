package com.ahmadre.hinata.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
	void givesUpWhenTheRequestRunsOutOfTimeAndHoldsNothingAgainstTheName() throws IOException {
		PublicDns dns = dns(hangingFor("slow"), Duration.ofSeconds(10));
		long started = System.nanoTime();

		assertThatThrownBy(() -> dns.within("ada", in(200), () -> dns.lookup("slow.test")))
				.isInstanceOf(PublicDns.SlowLookup.class);

		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
		released.countDown();
		// The request ran out of time, not the name: it is asked again.
		assertThat(dns.lookup("slow.test")).containsExactly(PUBLIC);
	}

	@Test
	void countsTheLookupsOfOneRequesterUntilTheyEnd() throws IOException {
		AtomicInteger askedForImages = new AtomicInteger();
		PublicDns dns = dns(host -> {
			if (host.startsWith("hang")) {
				await(released);
			}
			else {
				askedForImages.incrementAndGet();
			}
			return new InetAddress[] { PUBLIC };
		}, Duration.ofSeconds(10));
		for (int i = 0; i < 4; i++) {
			String hanging = "hang" + i + ".test";
			assertThatThrownBy(() -> dns.within("ada", in(50), () -> dns.lookup(hanging)))
					.isInstanceOf(PublicDns.SlowLookup.class);
		}

		// Ada's four given-up lookups still run: hers is refused before anyone is asked, Bea's is answered.
		assertThatThrownBy(() -> dns.within("ada", in(5_000), () -> dns.lookup("images.test")))
				.isInstanceOf(PublicDns.SlowLookup.class);
		assertThat(askedForImages).hasValue(0);
		assertThat(dns.within("bea", in(5_000), () -> dns.lookup("images.test"))).containsExactly(PUBLIC);
	}

	@Test
	void refusesANameThatJustTimedOutWithoutAskingAgain() {
		AtomicInteger asked = new AtomicInteger();
		PublicDns dns = dns(host -> {
			asked.incrementAndGet();
			await(released);
			return new InetAddress[] { PUBLIC };
		}, Duration.ofMillis(100));

		assertThatThrownBy(() -> dns.lookup("mute.test")).isInstanceOf(PublicDns.SlowLookup.class);
		assertThatThrownBy(() -> dns.lookup("mute.test")).isInstanceOf(PublicDns.SlowLookup.class);

		assertThat(asked).hasValue(1);
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

	/** Answers every host at once, except those starting with [prefix], which wait for the test to release them. */
	private PublicDns.Resolver hangingFor(String prefix) {
		return host -> {
			if (host.startsWith(prefix)) {
				await(released);
			}
			return new InetAddress[] { PUBLIC };
		};
	}

	private static long in(long millis) {
		return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
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
