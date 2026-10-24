package com.ahmadre.hinata.content;

import com.ahmadre.hinata.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every content surface by the kind of content it holds (HIN-169): the one way to find one. */
@Component
public class ContentSurfaces {

	private final Map<ContentKind, ContentSurface> byKind = new EnumMap<>(ContentKind.class);

	public ContentSurfaces(List<ContentSurface> surfaces) {
		surfaces.forEach(surface -> byKind.put(surface.kind(), surface));
	}

	/** The surface of [kind]; empty for a kind no surface holds, such as {@code OTHER}. */
	public Optional<ContentSurface> find(ContentKind kind) {
		return Optional.ofNullable(byKind.get(kind));
	}

	/** The surface of [kind]; 400 for a kind moderation cannot act on. */
	public ContentSurface require(ContentKind kind) {
		return find(kind).orElseThrow(() -> ApiException.badRequest("error.moderation.scopeNotAllowed"));
	}
}
