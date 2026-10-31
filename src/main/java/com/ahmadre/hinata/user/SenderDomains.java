package com.ahmadre.hinata.user;

import java.net.IDN;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The sender domain of an ingested mail, as a person trusts it with its images (HIN-181): the
 * host after the {@code @}, lower case, an internationalised name in its ASCII form, so
 * {@code Bücher.example} and {@code xn--bcher-kva.example} are one domain. Trusting a domain
 * covers its subdomains: whoever trusts {@code vodafone.com} gets the images of
 * {@code kundenservice.vodafone.com} too.
 */
public final class SenderDomains {

	private SenderDomains() {
	}

	/** Longest domain name DNS allows. */
	private static final int MAX_CHARS = 253;

	private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

	/** [domain] in normal form, or null when it is no domain name with at least two labels. */
	public static String normalize(String domain) {
		if (domain == null) return null;
		String trimmed = domain.strip();
		if (trimmed.endsWith(".")) trimmed = trimmed.substring(0, trimmed.length() - 1);
		if (trimmed.isEmpty() || trimmed.length() > MAX_CHARS) return null;
		String ascii;
		try {
			ascii = IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException notADomain) {
			return null;
		}
		if (ascii.length() > MAX_CHARS) return null;
		String[] labels = ascii.split("\\.", -1);
		if (labels.length < 2) return null;
		for (String label : labels) {
			if (!LABEL.matcher(label).matches()) return null;
		}
		return ascii;
	}

	/**
	 * Whether [domain], normalised, is a public suffix such as {@code com}, {@code co.uk} or
	 * {@code github.io}: a name under which anybody registers, so trusting it would trust everyone
	 * below it. A freemail domain such as {@code gmail.com} is not one.
	 */
	public static boolean isPublicSuffix(String domain) {
		if (domain == null || !com.google.common.net.InternetDomainName.isValid(domain)) return false;
		return com.google.common.net.InternetDomainName.from(domain).isPublicSuffix();
	}

	/** The domain of a mail address, normalised, or null. */
	public static String ofAddress(String address) {
		if (address == null) return null;
		int at = address.lastIndexOf('@');
		return at < 0 ? null : normalize(address.substring(at + 1));
	}

	/** Whether one of [trusted] is [domain] or a parent domain of it. */
	public static boolean covers(Collection<String> trusted, String domain) {
		if (domain == null) return false;
		for (String parent : trusted) {
			if (domain.equals(parent) || domain.endsWith("." + parent)) return true;
		}
		return false;
	}
}
