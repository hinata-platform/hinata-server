package com.ahmadre.hinata.content;

import java.util.List;

/**
 * Content whose copies are to be scrubbed, with what the copies were made from. Copies written
 * before they carried a pointer back are found by what they copied, so the original text travels
 * along: the quarantine holds it after a removal, the caller still has it before a deletion.
 *
 * @param authorId  who wrote it, for a comment; null otherwise
 * @param rootId    the root of the thread a comment stands in (its own id for a root)
 * @param title     the title as it was, when an issue's title or all of it went; null otherwise
 * @param textDoc   the text as it was (Lexical JSON), when a comment, a description or all of it
 *                  went; null otherwise
 * @param issueKeys the readable keys the issue has gone by, for the links old copies carry
 */
public record CopySource(ContentRef ref, String authorId, String rootId, String title, String textDoc,
		List<String> issueKeys) {
}
