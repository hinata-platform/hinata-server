package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.user.SenderDomains;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the server that received an ingested mail vouched for its sender (HIN-181): a
 * {@code From} header is anybody's word, so only a sender the receiving server verified is
 * offered to a reader as a domain to trust with images.
 *
 * <p>Read from the topmost {@code Authentication-Results} header (RFC 8601), the one the
 * receiving server added last; every header below it came with the mail and may say anything.
 * The sender counts as verified when that header reports {@code dmarc=pass}, or {@code dkim=pass}
 * for a signing domain aligned with the {@code From} domain: the same domain, or one of them a
 * parent of the other. SPF alone does not count; it vouches for the envelope, not the
 * {@code From} a reader sees.
 */
final class SenderVerification {

	private SenderVerification() {
	}

	/** Characters of the header read; a real one is a few hundred. */
	private static final int MAX_HEADER_CHARS = 4096;

	private static final Pattern DMARC_PASS = Pattern.compile("(?:^|[;\\s])dmarc\\s*=\\s*pass\\b");
	private static final Pattern DKIM_PASS = Pattern.compile("(?:^|[;\\s])dkim\\s*=\\s*pass\\b");
	private static final Pattern SIGNING_DOMAIN = Pattern.compile("header\\.d\\s*=\\s*([a-z0-9.-]{1,253})");

	/** Whether [headers], a message's {@code Authentication-Results} top first, vouch for [from]. */
	static boolean verified(String[] headers, String from) {
		if (headers == null || headers.length == 0 || headers[0] == null) return false;
		String domain = SenderDomains.ofAddress(from);
		if (domain == null) return false;
		String header = headers[0];
		if (header.length() > MAX_HEADER_CHARS) header = header.substring(0, MAX_HEADER_CHARS);
		header = header.toLowerCase(Locale.ROOT);
		// The authserv-id before the first ';' names the server, not a result.
		int first = header.indexOf(';');
		if (first < 0) return false;
		String results = header.substring(first + 1);
		if (DMARC_PASS.matcher(results).find()) return true;
		for (String result : results.split(";")) {
			if (!DKIM_PASS.matcher(result).find()) continue;
			Matcher signer = SIGNING_DOMAIN.matcher(result);
			if (signer.find() && aligned(SenderDomains.normalize(signer.group(1)), domain)) return true;
		}
		return false;
	}

	private static boolean aligned(String signer, String from) {
		return signer != null && (signer.equals(from) || from.endsWith("." + signer) || signer.endsWith("." + from));
	}
}
