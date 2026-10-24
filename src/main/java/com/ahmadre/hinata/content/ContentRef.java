package com.ahmadre.hinata.content;

/**
 * Where content lives once the server resolved it. {@code parentId} is the issue of a comment or
 * an attachment; {@code projectId} is always derived from the stored content, never taken from a
 * request, and empty for a person or a page outside a project.
 */
public record ContentRef(ContentKind kind, String id, String parentId, String projectId) {
}
