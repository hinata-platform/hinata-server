package com.ahmadre.hinata.ics;

import com.ahmadre.hinata.config.HinataProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts the address of a calendar subscription before it is stored.
 *
 * <p>A subscription URL is a credential in all but name: Google, Apple and Outlook
 * put a private token into it, and whoever holds the URL reads the calendar. So
 * it is kept encrypted at rest and appears in no log, audit entry or response;
 * the subscriber is shown a masked host instead.
 *
 * <p>This is deliberately not {@code git/TokenCipher}, which derives its key from
 * a secret with a hardcoded default: an instance that never set that secret
 * encrypts with a key everyone who reads the source knows. The key here comes
 * from {@code HINATA_ICS_SECRET} and from nowhere else. Without it
 * {@link #isConfigured()} is false, and callers refuse to store a subscription
 * at all rather than store it under a key that protects nothing.
 *
 * <p>The secret is Base64 of at least 32 bytes. It is hashed together with a
 * label into the AES-256 key, so every length from 32 bytes up is treated alike
 * and the same value pasted into a second variable still yields a different key.
 *
 * <p>The stored form is {@code v1:} followed by Base64 of a fresh 96-bit nonce and
 * the AES-GCM ciphertext with its tag. The prefix names the scheme so that a later
 * one can sit beside it. Authenticated along with the ciphertext, but not stored in
 * it, are that prefix and what the value is bound to: the record it is stored in, or
 * the person it belongs to. A value copied into somebody else's record does not
 * decrypt there, so it cannot open their calendar.
 */
@Slf4j
@Component
public class IcsUrlCipher {

	private static final String VERSION = "v1:";
	private static final byte[] VERSION_BYTES = VERSION.getBytes(StandardCharsets.US_ASCII);
	private static final String TRANSFORM = "AES/GCM/NoPadding";
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;

	/** The least key material accepted: 256 bits. */
	static final int MIN_SECRET_BYTES = 32;

	private static final byte[] KEY_LABEL = "hinata ics url v1".getBytes(StandardCharsets.US_ASCII);

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	@Autowired
	public IcsUrlCipher(HinataProperties properties) {
		this(properties.getIcs().getSecret());
	}

	IcsUrlCipher(String secret) {
		this.key = keyFrom(secret);
	}

	/** Whether a usable key is set. Nothing may be stored encrypted while this is false. */
	public boolean isConfigured() {
		return key != null;
	}

	/**
	 * Encrypts [url] for storage, bound to [boundTo]: the id of the record it goes into,
	 * or of the person it belongs to. Only {@link #decrypt} with the same binding reads it.
	 *
	 * @throws IllegalStateException when no key is configured; ask {@link #isConfigured()} first
	 */
	public String encrypt(String url, String boundTo) {
		SecretKey secretKey = requireKey();
		if (url == null || boundTo == null || boundTo.isBlank()) {
			throw new IllegalArgumentException("a calendar URL and what it is bound to are needed");
		}
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			random.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance(TRANSFORM);
			cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(associatedData(boundTo));
			byte[] sealed = cipher.doFinal(url.getBytes(StandardCharsets.UTF_8));
			byte[] stored = ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
			return VERSION + Base64.getEncoder().encodeToString(stored);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("calendar URL encryption failed", ex);
		}
	}

	/**
	 * Decrypts a value written by {@link #encrypt} with the same [boundTo].
	 *
	 * @throws IllegalArgumentException when the value was tampered with, written under
	 *         another key or for another record, or is not in this format; the message
	 *         quotes nothing
	 * @throws IllegalStateException when no key is configured
	 */
	public String decrypt(String stored, String boundTo) {
		SecretKey secretKey = requireKey();
		if (boundTo == null || boundTo.isBlank()) {
			throw new IllegalArgumentException("what the value is bound to is needed");
		}
		if (stored == null || !stored.startsWith(VERSION)) {
			throw unreadable();
		}
		byte[] sealed;
		try {
			sealed = Base64.getDecoder().decode(stored.substring(VERSION.length()));
		}
		catch (IllegalArgumentException ex) {
			throw unreadable();
		}
		if (sealed.length < NONCE_BYTES + TAG_BITS / 8) {
			throw unreadable();
		}
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORM);
			cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
			cipher.updateAAD(associatedData(boundTo));
			byte[] plain = cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		}
		catch (AEADBadTagException ex) {
			throw unreadable();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("calendar URL decryption failed", ex);
		}
	}

	private SecretKey requireKey() {
		if (key == null) {
			throw new IllegalStateException("HINATA_ICS_SECRET is not configured");
		}
		return key;
	}

	/** The version prefix and the binding, separated so that neither can borrow from the other. */
	private static byte[] associatedData(String boundTo) {
		byte[] binding = boundTo.getBytes(StandardCharsets.UTF_8);
		return ByteBuffer.allocate(VERSION_BYTES.length + 1 + binding.length)
				.put(VERSION_BYTES).put((byte) 0).put(binding).array();
	}

	/** A fresh exception without a cause, so nothing of the stored value travels with it. */
	private static IllegalArgumentException unreadable() {
		return new IllegalArgumentException("stored calendar URL cannot be decrypted with the configured key");
	}

	private static SecretKey keyFrom(String secret) {
		if (secret == null || secret.isBlank()) {
			return null;
		}
		String compact = secret.replaceAll("\\s", "");
		byte[] material;
		try {
			boolean urlSafe = compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0;
			material = (urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(compact);
		}
		catch (IllegalArgumentException ex) {
			log.error("HINATA_ICS_SECRET is not valid Base64, so calendar subscriptions stay off");
			return null;
		}
		try {
			if (material.length < MIN_SECRET_BYTES) {
				log.error("HINATA_ICS_SECRET holds {} bytes but needs at least {}, so calendar subscriptions stay off",
						material.length, MIN_SECRET_BYTES);
				return null;
			}
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(KEY_LABEL);
			return new SecretKeySpec(digest.digest(material), "AES");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
		finally {
			Arrays.fill(material, (byte) 0);
		}
	}
}
