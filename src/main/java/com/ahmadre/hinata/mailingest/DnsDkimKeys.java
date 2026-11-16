package com.ahmadre.hinata.mailingest;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.stereotype.Component;

import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * The DKIM keys signers publish, read from DNS through the system's resolvers (HIN-181). An answer
 * is kept for a while, since a mailbox read again holds many mails signed with the same key; a
 * failed lookup is not kept, so the next mail asks again.
 */
@Component
class DnsDkimKeys implements Dkim.KeyLookup {

	/** Longest TXT answer read; a 4096-bit key is under 800 characters. */
	private static final int MAX_CHARS = 8192;

	private final Cache<String, List<String>> answers = CacheBuilder.newBuilder()
			.maximumSize(1000)
			.expireAfterWrite(Duration.ofMinutes(15))
			.build();

	/**
	 * Names, and the signing domains in them, DNS failed to answer for, kept a few minutes: a
	 * mailbox full of mails from one signer whose DNS is down would otherwise wait out the timeout
	 * once per mail, and once more for every other selector of that domain.
	 */
	private final Cache<String, IOException> failures = CacheBuilder.newBuilder()
			.maximumSize(1000)
			.expireAfterWrite(Duration.ofMinutes(5))
			.build();

	/** Asks DNS itself, past both caches. */
	private final Dkim.KeyLookup resolver;

	DnsDkimKeys() {
		this(new Dkim.KeyLookup() {
			@Override
			public List<String> txt(String name) throws IOException {
				return lookup(name, Duration.ofSeconds(3));
			}

			@Override
			public List<String> txt(String name, Duration remaining) throws IOException {
				return lookup(name, remaining);
			}
		});
	}

	/** Over [resolver] instead of DNS, for tests. */
	DnsDkimKeys(Dkim.KeyLookup resolver) {
		this.resolver = resolver;
	}

	@Override
	public List<String> txt(String name) throws IOException {
		return txt(name, Duration.ofSeconds(3));
	}

	@Override
	public List<String> txt(String name, Duration remaining) throws IOException {
		List<String> known = answers.getIfPresent(name);
		if (known != null) return known;
		String domain = domainOf(name);
		IOException failed = failures.getIfPresent(name);
		if (failed == null && domain != null) failed = failures.getIfPresent(domain);
		if (failed != null) throw new IOException(failed.getMessage());
		try {
			List<String> records = resolver.txt(name, remaining);
			answers.put(name, records);
			return records;
		}
		catch (IOException noAnswer) {
			failures.put(name, noAnswer);
			if (domain != null) failures.put(domain, noAnswer);
			throw noAnswer;
		}
	}

	/** The signing domain in a key's name, {@code selector._domainkey.domain}; null for another name. */
	static String domainOf(String name) {
		int at = name.indexOf("._domainkey.");
		return at < 0 ? null : name.substring(at + "._domainkey.".length());
	}

	/**
	 * How long JNDI waits for one answer and how often it asks again, so a lookup ends within
	 * [remaining]: one second at most, and no second try when less than three seconds are left
	 * (a retry doubles the wait).
	 */
	static int[] timeouts(Duration remaining) {
		long millis = Math.max(1, remaining.toMillis());
		return new int[] { (int) Math.min(1000, millis), millis < 3000 ? 0 : 1 };
	}

	private static List<String> lookup(String name, Duration remaining) throws IOException {
		int[] timeouts = timeouts(remaining);
		Hashtable<String, String> env = new Hashtable<>();
		env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
		env.put("java.naming.provider.url", "dns:");
		env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(timeouts[0]));
		env.put("com.sun.jndi.dns.timeout.retries", String.valueOf(timeouts[1]));
		InitialDirContext context = null;
		try {
			context = new InitialDirContext(env);
			Attribute txt = context.getAttributes(name, new String[] { "TXT" }).get("TXT");
			List<String> records = new ArrayList<>();
			if (txt == null) return records;
			for (int i = 0; i < txt.size(); i++) {
				Object value = txt.get(i);
				if (value != null) records.add(joined(value.toString()));
			}
			return records;
		}
		catch (NameNotFoundException none) {
			return List.of();
		}
		catch (NamingException failed) {
			throw new IOException("DNS lookup of " + name + " failed: " + failed.getMessage(), failed);
		}
		finally {
			if (context != null) {
				try {
					context.close();
				}
				catch (NamingException ignored) {
					// nothing held open
				}
			}
		}
	}

	/**
	 * One TXT record as one string. The JDK hands a record of several strings over either quoted
	 * ({@code "v=DKIM1; p=MIIB" "IjAN"}) or joined by a space; quotes are taken off and the parts
	 * joined, and a space left between parts falls away where the tags are read.
	 */
	static String joined(String record) {
		String text = record.length() > MAX_CHARS ? record.substring(0, MAX_CHARS) : record;
		if (!text.startsWith("\"")) return text;
		StringBuilder out = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && quoted && i + 1 < text.length()) {
				out.append(text.charAt(++i));
			}
			else if (c == '"') {
				quoted = !quoted;
			}
			else if (quoted) {
				out.append(c);
			}
		}
		return out.toString();
	}
}
