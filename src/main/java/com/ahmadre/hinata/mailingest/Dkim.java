package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.user.SenderDomains;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the DKIM signatures of a whole message (RFC 6376) against the keys their signers publish
 * in DNS, so the server can tell for itself whether a mail really comes from the domain that
 * signed it: no receiving mail server has to vouch for it first (HIN-181).
 *
 * <p>What counts is deliberately narrower than what RFC 6376 allows, because a pass here lets
 * a reader trust a domain with images:
 * <ul>
 *   <li>{@code rsa-sha256} with a key of at least 1024 bits, and {@code ed25519-sha256} (RFC 8463).
 *       {@code rsa-sha1} is refused (RFC 8301).</li>
 *   <li>The signature covers {@code From}, and the message has exactly one {@code From} header.</li>
 *   <li>A signature with a body length ({@code l=}) shorter than the body passes for nothing:
 *       anything could have been appended below the signed part.</li>
 *   <li>A header the reader sees or the parser reads ({@code From}, {@code Subject},
 *       {@code Content-Type}, ...) that the signature covers may not appear more often than the
 *       signature lists it: the parser reads the topmost one, a signature the bottom ones, so an
 *       extra copy on top would be shown without being signed.</li>
 *   <li>The expiry ({@code x=}) is not enforced: a mailbox is read again long after the mail
 *       arrived, and what matters is who wrote it, not whether it is fresh.</li>
 * </ul>
 *
 * <p>Pure: the keys come through a {@link KeyLookup}, so tests need no network.
 */
final class Dkim {

	private Dkim() {
	}

	/** The TXT records at a DNS name. */
	@FunctionalInterface
	interface KeyLookup {

		/**
		 * The TXT records published at [name], each with its strings joined; empty when there is
		 * none.
		 *
		 * @throws IOException when DNS could not be asked, so the answer is not known yet
		 */
		List<String> txt(String name) throws IOException;
	}

	/**
	 * What the signatures of a message say.
	 *
	 * @param signed         whether it carries any DKIM signature at all
	 * @param passed         the signing domains ({@code d=}) whose signature holds
	 * @param keyUnavailable whether some signature could not be checked because DNS did not answer
	 */
	record Result(boolean signed, Set<String> passed, boolean keyUnavailable) {
	}

	/** Signatures of one message checked at most: each one costs a DNS lookup. */
	static final int MAX_SIGNATURES = 5;

	/** Headers in a message read at most; a real one has fewer than two hundred. */
	private static final int MAX_HEADERS = 1000;

	/** Headers that may not carry an unsigned copy above the signed ones, see the class comment. */
	private static final Set<String> SINGLE = Set.of("from", "subject", "content-type",
			"content-transfer-encoding", "mime-version", "reply-to", "sender", "to", "cc", "date");

	private static final Pattern SELECTOR = Pattern.compile("[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?"
			+ "(?:\\.[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?)*");

	private static final Pattern FWS = Pattern.compile("[ \t\r\n]+");

	/** The DER prefix of an Ed25519 SubjectPublicKeyInfo; the 32 key bytes follow it. */
	private static final byte[] ED25519_SPKI = { 0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21,
			0x00 };

	/** Checks every DKIM signature of [raw], the message exactly as it was received. */
	static Result verify(byte[] raw, KeyLookup keys) {
		// ISO-8859-1 maps every byte to one char and back, so canonical forms keep the bytes.
		String message = new String(raw, StandardCharsets.ISO_8859_1);
		message = message.replaceAll("(?<!\r)\n", "\r\n");
		int split = message.indexOf("\r\n\r\n");
		String head = split < 0 ? message + "\r\n" : message.substring(0, split + 2);
		String body = split < 0 ? "" : message.substring(split + 4);
		List<Header> headers = headers(head);
		List<Header> signatures = headers.stream().filter(h -> h.name.equals("dkim-signature")).toList();
		if (signatures.isEmpty()) return new Result(false, Set.of(), false);
		if (headers.stream().filter(h -> h.name.equals("from")).count() != 1) {
			return new Result(true, Set.of(), false);
		}
		Set<String> passed = new LinkedHashSet<>();
		boolean unavailable = false;
		Map<String, String> bodyHashes = new HashMap<>();
		for (Header signature : signatures.subList(0, Math.min(signatures.size(), MAX_SIGNATURES))) {
			try {
				String domain = check(signature, headers, body, bodyHashes, keys);
				if (domain != null) passed.add(domain);
			}
			catch (IOException noAnswer) {
				unavailable = true;
			}
		}
		return new Result(true, Set.copyOf(passed), unavailable);
	}

	/** One header field: its name in lower case and the field exactly as it stood, folds and all. */
	private record Header(String name, String raw) {

		/** Its value, unfolded. */
		String value() {
			return raw.substring(raw.indexOf(':') + 1).replace("\r\n", "");
		}
	}

	private static List<Header> headers(String head) {
		List<Header> headers = new ArrayList<>();
		StringBuilder current = null;
		for (String line : head.split("\r\n", -1)) {
			if (line.isEmpty()) continue;
			if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && current != null) {
				current.append("\r\n").append(line);
				continue;
			}
			if (current != null) add(headers, current.toString());
			if (headers.size() >= MAX_HEADERS) return headers;
			current = new StringBuilder(line);
		}
		if (current != null) add(headers, current.toString());
		return headers;
	}

	private static void add(List<Header> headers, String raw) {
		int colon = raw.indexOf(':');
		if (colon <= 0) return;
		headers.add(new Header(raw.substring(0, colon).strip().toLowerCase(Locale.ROOT), raw));
	}

	/**
	 * The signing domain of [signature] when it holds for [headers] and [body], else null.
	 *
	 * @throws IOException when its key could not be looked up
	 */
	private static String check(Header signature, List<Header> headers, String body, Map<String, String> bodyHashes,
			KeyLookup keys) throws IOException {
		Map<String, String> tags = tags(signature.value());
		if (tags == null || !"1".equals(tags.get("v"))) return null;
		String algorithm = tags.getOrDefault("a", "").toLowerCase(Locale.ROOT);
		if (!algorithm.equals("rsa-sha256") && !algorithm.equals("ed25519-sha256")) return null;
		String domain = SenderDomains.normalize(tags.get("d"));
		String selector = tags.getOrDefault("s", "").toLowerCase(Locale.ROOT);
		if (domain == null || !SELECTOR.matcher(selector).matches()) return null;
		String identity = tags.get("i");
		if (identity != null && !identityWithin(identity, domain)) return null;
		String query = tags.get("q");
		if (query != null && !query.toLowerCase(Locale.ROOT).startsWith("dns/txt")) return null;
		String[] canon = tags.getOrDefault("c", "simple/simple").toLowerCase(Locale.ROOT).split("/", -1);
		String headerCanon = canon[0];
		String bodyCanon = canon.length > 1 ? canon[1] : "simple";
		if (canon.length > 2 || !isCanon(headerCanon) || !isCanon(bodyCanon)) return null;
		List<String> signed = new ArrayList<>();
		for (String name : tags.getOrDefault("h", "").split(":")) {
			String trimmed = name.strip().toLowerCase(Locale.ROOT);
			if (!trimmed.isEmpty()) signed.add(trimmed);
		}
		if (!signed.contains("from") || !noUnsignedCopies(signed, headers)) return null;
		byte[] signatureBytes;
		byte[] claimedBodyHash;
		try {
			signatureBytes = Base64.getDecoder().decode(tags.getOrDefault("b", ""));
			claimedBodyHash = Base64.getDecoder().decode(tags.getOrDefault("bh", ""));
		}
		catch (IllegalArgumentException notBase64) {
			return null;
		}
		if (signatureBytes.length == 0 || claimedBodyHash.length == 0) return null;

		String canonicalBody = bodyHashes.computeIfAbsent(bodyCanon, c -> c.equals("relaxed") ? relaxedBody(body)
				: simpleBody(body));
		String length = tags.get("l");
		if (length != null && !length.equals(String.valueOf(canonicalBody.length()))) return null;
		if (!MessageDigest.isEqual(sha256(canonicalBody), claimedBodyHash)) return null;

		PublicKey key = key(keys.txt(selector + "._domainkey." + domain), algorithm, identity, domain);
		if (key == null) return null;
		byte[] data = signedData(signature, headers, signed, headerCanon).getBytes(StandardCharsets.ISO_8859_1);
		try {
			Signature verifier;
			if (algorithm.equals("rsa-sha256")) {
				verifier = Signature.getInstance("SHA256withRSA");
				verifier.initVerify(key);
				verifier.update(data);
			}
			else {
				// RFC 8463: Ed25519 signs the SHA-256 hash of the header data.
				verifier = Signature.getInstance("Ed25519");
				verifier.initVerify(key);
				verifier.update(MessageDigest.getInstance("SHA-256").digest(data));
			}
			return verifier.verify(signatureBytes) ? domain : null;
		}
		catch (GeneralSecurityException broken) {
			return null;
		}
	}

	private static boolean isCanon(String name) {
		return name.equals("simple") || name.equals("relaxed");
	}

	/** Whether the agent identity {@code i=} lies within [domain], as RFC 6376 demands. */
	private static boolean identityWithin(String identity, String domain) {
		int at = identity.lastIndexOf('@');
		String host = SenderDomains.normalize(at < 0 ? identity : identity.substring(at + 1));
		return host != null && (host.equals(domain) || host.endsWith("." + domain));
	}

	/**
	 * Whether every header in the [signed] list appears in the message no more often than the list
	 * names it, for the headers in {@link #SINGLE}.
	 */
	private static boolean noUnsignedCopies(List<String> signed, List<Header> headers) {
		for (String name : SINGLE) {
			long listed = signed.stream().filter(name::equals).count();
			if (listed == 0) continue;
			long present = headers.stream().filter(h -> h.name.equals(name)).count();
			if (present > listed) return false;
		}
		return true;
	}

	/**
	 * The header data a signature signs: each header it names, the bottom-most unused copy first,
	 * then the signature itself with an empty {@code b=}, without its line break (RFC 6376 3.7).
	 */
	private static String signedData(Header signature, List<Header> headers, List<String> signed, String canon) {
		StringBuilder data = new StringBuilder();
		Map<String, Integer> used = new HashMap<>();
		for (String name : signed) {
			int skip = used.merge(name, 1, Integer::sum);
			Header found = null;
			for (int i = headers.size() - 1; i >= 0 && skip > 0; i--) {
				if (headers.get(i).name.equals(name) && --skip == 0) found = headers.get(i);
			}
			// A header named but missing signs as nothing.
			if (found != null) data.append(canonical(found.raw, canon)).append("\r\n");
		}
		data.append(canonical(withoutSignatureValue(signature.raw), canon));
		return data.toString();
	}

	/** [raw] with the value of its {@code b=} tag removed, everything else as it was. */
	static String withoutSignatureValue(String raw) {
		int colon = raw.indexOf(':');
		StringBuilder out = new StringBuilder(raw.substring(0, colon + 1));
		String value = raw.substring(colon + 1);
		int start = 0;
		while (start <= value.length()) {
			int end = value.indexOf(';', start);
			if (end < 0) end = value.length();
			String spec = value.substring(start, end);
			int equals = spec.indexOf('=');
			if (equals > 0 && spec.substring(0, equals).replaceAll("[ \t\r\n]", "").equals("b")) {
				out.append(spec, 0, equals + 1);
			}
			else {
				out.append(spec);
			}
			if (end < value.length()) out.append(';');
			start = end + 1;
		}
		return out.toString();
	}

	private static String canonical(String raw, String canon) {
		if (canon.equals("simple")) return raw;
		int colon = raw.indexOf(':');
		String name = raw.substring(0, colon).strip().toLowerCase(Locale.ROOT);
		String value = raw.substring(colon + 1).replace("\r\n", "").replaceAll("[ \t]+", " ").strip();
		return name + ":" + value;
	}

	/** RFC 6376 3.4.4: whitespace runs to one space, none at line ends, no empty lines at the end. */
	static String relaxedBody(String body) {
		StringBuilder out = new StringBuilder(body.length());
		for (String line : body.split("\r\n", -1)) {
			out.append(line.replaceAll("[ \t]+", " ").replaceAll(" $", "")).append("\r\n");
		}
		String text = out.toString();
		while (text.endsWith("\r\n\r\n")) text = text.substring(0, text.length() - 2);
		return text.equals("\r\n") ? "" : text;
	}

	/** RFC 6376 3.4.3: the body as it is, with the empty lines at its end reduced to one break. */
	static String simpleBody(String body) {
		String text = body;
		while (text.endsWith("\r\n\r\n")) text = text.substring(0, text.length() - 2);
		if (text.isEmpty()) return "\r\n";
		return text.endsWith("\r\n") ? text : text + "\r\n";
	}

	/**
	 * The public key from the TXT records at the selector, or null when there is none that fits:
	 * revoked, of another type, restricted to other hash algorithms, too short, or strict about
	 * the identity while [identity] is a subdomain.
	 */
	private static PublicKey key(List<String> records, String algorithm, String identity, String domain) {
		for (String record : records) {
			Map<String, String> tags = tags(record);
			if (tags == null) continue;
			String version = tags.get("v");
			if (version != null && !version.equals("DKIM1")) continue;
			String type = tags.getOrDefault("k", "rsa").toLowerCase(Locale.ROOT);
			if (!algorithm.startsWith(type + "-")) continue;
			String hashes = tags.get("h");
			if (hashes != null && !List.of(hashes.toLowerCase(Locale.ROOT).split(":")).contains("sha256")) continue;
			String services = tags.get("s");
			if (services != null && !List.of(services.split(":")).contains("*")
					&& !List.of(services.split(":")).contains("email")) {
				continue;
			}
			String flags = tags.get("t");
			if (flags != null && List.of(flags.split(":")).contains("s") && identity != null) {
				int at = identity.lastIndexOf('@');
				if (!domain.equals(SenderDomains.normalize(identity.substring(at + 1)))) continue;
			}
			String encoded = tags.get("p");
			if (encoded == null || encoded.isEmpty()) continue; // revoked
			try {
				byte[] bytes = Base64.getDecoder().decode(encoded);
				if (type.equals("ed25519")) {
					if (bytes.length != 32) continue;
					byte[] spki = new byte[ED25519_SPKI.length + 32];
					System.arraycopy(ED25519_SPKI, 0, spki, 0, ED25519_SPKI.length);
					System.arraycopy(bytes, 0, spki, ED25519_SPKI.length, 32);
					return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
				}
				PublicKey rsa = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
				if (((RSAPublicKey) rsa).getModulus().bitLength() < 1024) continue;
				return rsa;
			}
			catch (IllegalArgumentException | GeneralSecurityException | ClassCastException unusable) {
				// another record may still hold a usable key
			}
		}
		return null;
	}

	/**
	 * A tag list ({@code a=1; b=2}, RFC 6376 3.2) with all whitespace inside values removed, or
	 * null when a tag appears twice, which makes the whole list invalid.
	 */
	static Map<String, String> tags(String list) {
		Map<String, String> tags = new LinkedHashMap<>();
		for (String spec : list.split(";")) {
			int equals = spec.indexOf('=');
			if (equals < 0) {
				if (spec.isBlank()) continue;
				return null;
			}
			String name = spec.substring(0, equals).strip();
			String value = FWS.matcher(spec.substring(equals + 1)).replaceAll("");
			if (name.isEmpty() || tags.put(name, value) != null) return null;
		}
		return tags;
	}

	private static byte[] sha256(String text) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.ISO_8859_1));
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
