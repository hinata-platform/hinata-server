package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.ics.IcsUrlCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Keeps the content key of a Live Activity encrypted at rest (HIN-103).
 *
 * <p>The key opens the state of somebody's running timer, so a database dump
 * must not hand it out. It is sealed with the server cipher that already guards
 * calendar addresses ({@link IcsUrlCipher}, keyed by {@code HINATA_ICS_SECRET}),
 * bound to the person and the activity: a value copied into another token
 * document does not open there. Rotating that secret makes the stored keys
 * unreadable, which costs nothing here — an activity lives a few hours, and
 * the next one registers a fresh key.
 *
 * <p>Without the secret nothing is stored: {@link #available()} is false and
 * the registration is refused, so no key ever sits in the database in clear.
 */
@Component
@RequiredArgsConstructor
public class LiveActivityKeys {

	/** AES-256: the app makes 32 random bytes per activity. */
	public static final int KEY_BYTES = 32;

	private final IcsUrlCipher cipher;

	public boolean available() {
		return cipher.isConfigured();
	}

	/** The stored form of [key] for [userId]'s activity [activityId]. */
	public String seal(byte[] key, String userId, String activityId) {
		return cipher.encrypt(Base64.getEncoder().encodeToString(key), binding(userId, activityId));
	}

	/**
	 * The key of [token], or throws {@link IllegalArgumentException} when it was
	 * stored for somebody else or under another secret.
	 */
	public byte[] open(DeviceToken token) {
		String plain = cipher.decrypt(token.getContentKey(), binding(token.getUserId(), token.getActivityId()));
		return Base64.getDecoder().decode(plain);
	}

	private static String binding(String userId, String activityId) {
		return "live-activity\u0000" + userId + "\u0000" + activityId;
	}
}
