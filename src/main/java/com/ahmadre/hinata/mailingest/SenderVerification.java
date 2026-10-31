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
 * <p>Read from the topmost {@code Authentication-Results} header (RFC 8601), and only when its
 * authserv-id is the one the ingest connection names: the receiving server adds its header on
 * top, and every header below it, or any header when that server adds none, came with the mail
 * and may say anything. The sender counts as verified when that header reports
 * {@code dmarc=pass} for the {@code From} domain itself ({@code header.from}), or
 * {@code dkim=pass} for a signing domain aligned with it: the same domain, or one of them a
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
	private static final Pattern FROM_DOMAIN = Pattern.compile("header\\.from\\s*=\\s*([a-z0-9.-]{1,253})");

	/**
	 * Whether [headers], a message's {@code Authentication-Results} top first, vouch for [from],
	 * as written by the server [authservId] names; never without one.
	 */
	static boolean verified(String[] headers, String from, String authservId) {
		if (headers == null || headers.length == 0 || headers[0] == null) return false;
		if (authservId == null || authservId.isBlank()) return false;
		String domain = SenderDomains.ofAddress(from);
		if (domain == null) return false;
		String header = headers[0];
		if (header.length() > MAX_HEADER_CHARS) header = header.substring(0, MAX_HEADER_CHARS);
		header = header.toLowerCase(Locale.ROOT);
		// The authserv-id before the first ';' names the server, perhaps with a version after it.
		int first = header.indexOf(';');
		if (first < 0) return false;
		String[] server = header.substring(0, first).strip().split("\\s+");
		if (!server[0].equals(authservId.strip().toLowerCase(Locale.ROOT))) return false;
		String results = header.substring(first + 1);
		for (String result : results.split(";")) {
			if (DMARC_PASS.matcher(result).find()) {
				Matcher author = FROM_DOMAIN.matcher(result);
				if (author.find() && domain.equals(SenderDomains.normalize(author.group(1)))) return true;
			}
			else if (DKIM_PASS.matcher(result).find()) {
				Matcher signer = SIGNING_DOMAIN.matcher(result);
				if (signer.find() && aligned(SenderDomains.normalize(signer.group(1)), domain)) return true;
			}
		}
		return false;
	}

	private static boolean aligned(String signer, String from) {
		return signer != null && (signer.equals(from) || from.endsWith("." + signer) || signer.endsWith("." + from));
	}
}
