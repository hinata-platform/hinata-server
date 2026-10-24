package com.ahmadre.hinata.content;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a request names: the kind, the id and, for an attachment, the issue it lives in. Nothing
 * here is trusted; a surface resolves it into a {@link ContentRef}.
 */
public record TargetRef(@NotNull ContentKind kind, @Size(max = 64) String id, @Size(max = 64) String parentId) {

	public static TargetRef of(ContentKind kind, String id) {
		return new TargetRef(kind, id, null);
	}

	/** What a request would name to reach [ref] again. */
	public static TargetRef of(ContentRef ref) {
		return new TargetRef(ref.kind(), ref.id(), ref.parentId());
	}
}
