package com.ahmadre.hinata.content;

/**
 * What a request names: the kind, the id and, for an attachment, the issue it lives in. Nothing
 * here is trusted; a surface resolves it into a {@link ContentRef}.
 */
public record TargetRef(ContentKind kind, String id, String parentId) {

	public static TargetRef of(ContentKind kind, String id) {
		return new TargetRef(kind, id, null);
	}
}
