package com.ahmadre.hinata.content;

import java.time.Instant;

/** One saved version of a page, as the quarantine keeps it. */
public record VersionCopy(int number, String title, String contentDoc, String editorId, Instant savedAt) {
}
