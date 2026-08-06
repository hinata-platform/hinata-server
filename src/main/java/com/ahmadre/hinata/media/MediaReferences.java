package com.ahmadre.hinata.media;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one definition of "which stored objects does this body embed".
 *
 * <p>Extracted from {@link MediaGarbageCollector}, which had it first and for the
 * opposite purpose: the sweep asks the question to decide what is <em>not</em>
 * referenced and may be reaped, and the freeze path asks it to decide what has to
 * stop being served. Two answers to that question that can disagree is the worst
 * arrangement available — the sweep would delete bytes the freeze is preserving,
 * or the freeze would leave served bytes the sweep knows are embedded — so there
 * is one pattern and one extractor, here.
 *
 * <p>Bodies are Lexical documents, and an image node stores the same
 * {@code /api/v1/media/{uuid}} URL the markdown used to. Both the document field
 * and the plain-text projection beside it are scanned by callers for the reason
 * the sweep documents: a document written before the Lexical migration still has
 * its markdown in the plain field.
 */
public final class MediaReferences {

	/** Every {@code /api/v1/media/{uuid}} reference in a chunk of content. */
	static final Pattern REFERENCE = Pattern.compile("/api/v1/media/([0-9a-fA-F-]{36})");

	private MediaReferences() {
	}

	/** The media ids referenced anywhere in [contents]; never null. */
	public static Set<String> idsIn(String... contents) {
		Set<String> ids = new LinkedHashSet<>();
		if (contents != null) {
			for (String content : contents) {
				collect(content, ids);
			}
		}
		return ids;
	}

	/**
	 * The storage keys behind {@link #idsIn}, ready to be frozen.
	 *
	 * <p>The key rather than the id, because that is what the byte guard at
	 * {@code StorageService.getObject} matches on — it is handed an object key and
	 * cannot walk back to whatever entity embedded it.
	 */
	public static Set<String> objectKeysIn(String... contents) {
		Set<String> keys = new LinkedHashSet<>();
		for (String id : idsIn(contents)) {
			keys.add(MediaService.PREFIX + id);
		}
		return keys;
	}

	/** Adds every media id in [content] to [into]. */
	static void collect(String content, Set<String> into) {
		if (content == null || content.isEmpty()) {
			return;
		}
		Matcher matcher = REFERENCE.matcher(content);
		while (matcher.find()) {
			into.add(matcher.group(1));
		}
	}
}
