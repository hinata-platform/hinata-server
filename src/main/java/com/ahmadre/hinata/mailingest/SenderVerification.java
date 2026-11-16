package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.user.SenderDomains;
import com.google.common.net.InternetDomainName;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether an ingested mail really comes from the domain in its {@code From} header (HIN-181): a
 * {@code From} header is anybody's word, so only a verified sender is offered to a reader as a
 * domain to trust with images.
 *
 * <p>The server checks for itself: a DKIM signature of the whole message ({@link Dkim}) whose
 * signing domain is aligned with the {@code From} domain, as DMARC defines alignment (the same
 * organisational domain, RFC 7489 3.1.1). That is what DMARC accepts as a pass, and it needs
 * neither a receiving mail server that checks nor anything configured.
 *
 * <p>A receiving server's word counts as well, when the ingest connection names it: the topmost
 * {@code Authentication-Results} header (RFC 8601), and only when its authserv-id is that name.
 * The receiving server adds its header on top, and every header below it, or any header when
 * that server adds none, came with the mail and may say anything. It vouches with
 * {@code dmarc=pass} for the {@code From} domain itself ({@code header.from}), or
 * {@code dkim=pass} for a signing domain aligned with it ({@code header.d}, or the domain of
 * {@code header.i}, which RFC 6376 keeps within the signing domain). SPF alone does not count; it
 * vouches for the envelope, not the {@code From} a reader sees.
 */
final class SenderVerification {

	private SenderVerification() {
	}

	/** What the check says about a mail's sender. */
	enum Verdict {
		/** A signature aligned with the {@code From} domain holds, or the receiving server said so. */
		VERIFIED,
		/** The mail carries no DKIM signature, so who sent it cannot be told. */
		UNSIGNED,
		/** It is signed, but no signature holds that is aligned with the {@code From} domain. */
		UNVERIFIED,
		/** A signer's key could not be looked up right now; the answer is not known. */
		UNKNOWN
	}

	/** Characters of the header read; a real one is a few hundred. */
	private static final int MAX_HEADER_CHARS = 4096;

	private static final Pattern DMARC_PASS = Pattern.compile("(?:^|[;\\s])dmarc\\s*=\\s*pass\\b");
	private static final Pattern DKIM_PASS = Pattern.compile("(?:^|[;\\s])dkim\\s*=\\s*pass\\b");
	private static final Pattern SIGNING_DOMAIN = Pattern.compile("header\\.d\\s*=\\s*([a-z0-9.-]{1,253})");
	private static final Pattern SIGNING_IDENTITY = Pattern.compile("header\\.i\\s*=\\s*[^@\\s;]*@([a-z0-9.-]{1,253})");
	private static final Pattern FROM_DOMAIN = Pattern.compile("header\\.from\\s*=\\s*([a-z0-9.-]{1,253})");

	/**
	 * The verdict on [from], the address the issue is attributed to, for a message whose bytes as
	 * received are [raw] (null when they could not be read) and whose
	 * {@code Authentication-Results} headers, top first, are [authResults].
	 */
	static Verdict check(byte[] raw, String from, String[] authResults, String authservId, Dkim.KeyLookup keys) {
		if (verified(authResults, from, authservId)) return Verdict.VERIFIED;
		if (raw == null) return Verdict.UNVERIFIED;
		String domain = SenderDomains.ofAddress(from);
		Dkim.Result signatures = Dkim.verify(raw, domain, keys);
		if (!signatures.signed()) return Verdict.UNSIGNED;
		// The address the issue is attributed to has to be the one the signature signs.
		if (!signatures.passed().isEmpty() && from != null && from.equalsIgnoreCase(signatures.from())) {
			return Verdict.VERIFIED;
		}
		return signatures.keyUnavailable() ? Verdict.UNKNOWN : Verdict.UNVERIFIED;
	}

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
				Matcher identity = SIGNING_IDENTITY.matcher(result);
				String signing = signer.find() ? signer.group(1) : identity.find() ? identity.group(1) : null;
				if (aligned(SenderDomains.normalize(signing), domain)) return true;
			}
		}
		return false;
	}

	/**
	 * Whether [signer] is aligned with [from] in DMARC's relaxed sense: the same domain, or both
	 * under the same organisational domain, the one registered below a public suffix. Two
	 * customers of one public suffix ({@code a.github.io}, {@code b.github.io}) are not aligned.
	 */
	static boolean aligned(String signer, String from) {
		if (signer == null || from == null) return false;
		if (signer.equals(from)) return true;
		String organisation = organisational(from);
		return organisation != null && organisation.equals(organisational(signer));
	}

	private static String organisational(String domain) {
		if (!InternetDomainName.isValid(domain)) return null;
		InternetDomainName name = InternetDomainName.from(domain);
		if (!name.isUnderPublicSuffix()) return null;
		return name.topPrivateDomain().toString();
	}
}
