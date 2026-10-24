package com.ahmadre.hinata.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Content as moderation sees it: who wrote it, what it says, where it stands.
 *
 * @param externalSender  the sender address of content that came in by mail, for a neutral
 *                        notice; null for every other origin
 * @param contributorIds  everyone who wrote the state that is seen, at most fifty
 * @param lastEditorId    who changed it last, the suggested person to be told about a decision
 * @param excerpt         the text, plain, at most {@value #EXCERPT_MAX} characters
 * @param context         what it belongs to, at most {@value #CONTEXT_MAX} characters
 * @param location        where it is found, as a person would name it ({@code MESSE-14})
 * @param contentHash     SHA-256 over the stored text and object keys, to tell whether it
 *                        changed since it was reported
 * @param removed         moderation removed all of it already; reporting it again answers 410
 */
public record ContentSnapshot(
		ContentRef ref,
		ContentOrigin origin,
		String externalSender,
		Set<String> contributorIds,
		String lastEditorId,
		String title,
		String excerpt,
		String context,
		String location,
		List<MediaRef> media,
		Instant createdAt,
		Instant editedAt,
		String contentHash,
		boolean removed) {

	public static final int EXCERPT_MAX = 4000;
	public static final int CONTEXT_MAX = 200;
	public static final int CONTRIBUTORS_MAX = 50;

	/** [text] cut to [max] characters, an ellipsis marking the cut; empty for none. */
	public static String clip(String text, int max) {
		if (text == null) {
			return "";
		}
		String plain = text.strip();
		return plain.length() <= max ? plain : plain.substring(0, max - 1) + "…";
	}

	/** SHA-256 over [parts] in order, a missing part counting as empty; hex. */
	public static String hashOf(String... parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String part : parts) {
				digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
				// A separator, so ("ab", "c") and ("a", "bc") hash apart.
				digest.update((byte) 0);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
