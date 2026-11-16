package com.ahmadre.hinata.mailingest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The DKIM key lookup's caches (HIN-181): answers for a while, failures for a few minutes. */
class DnsDkimKeysTest {

	@Test
	void anAnswerIsAskedForOnce() throws IOException {
		AtomicInteger asked = new AtomicInteger();
		DnsDkimKeys keys = new DnsDkimKeys(name -> {
			asked.incrementAndGet();
			return List.of("v=DKIM1; p=abc");
		});

		keys.txt("s._domainkey.example.org");
		keys.txt("s._domainkey.example.org");

		assertThat(asked).hasValue(1);
	}

	/** A signer whose DNS is down must not cost the timeout once per mail of a whole mailbox. */
	@Test
	void aFailedLookupIsNotRepeatedForAWhile() {
		AtomicInteger asked = new AtomicInteger();
		DnsDkimKeys keys = new DnsDkimKeys(name -> {
			asked.incrementAndGet();
			throw new IOException("timeout");
		});

		assertThatThrownBy(() -> keys.txt("s._domainkey.example.org")).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> keys.txt("s._domainkey.example.org")).isInstanceOf(IOException.class);

		assertThat(asked).hasValue(1);
	}
}
