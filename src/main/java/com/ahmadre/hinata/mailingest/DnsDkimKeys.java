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

	@Override
	public List<String> txt(String name) throws IOException {
		List<String> known = answers.getIfPresent(name);
		if (known != null) return known;
		List<String> records = lookup(name);
		answers.put(name, records);
		return records;
	}

	private static List<String> lookup(String name) throws IOException {
		Hashtable<String, String> env = new Hashtable<>();
		env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
		env.put("java.naming.provider.url", "dns:");
		env.put("com.sun.jndi.dns.timeout.initial", "2000");
		env.put("com.sun.jndi.dns.timeout.retries", "2");
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
