package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;

/**
 * Other content that showed an image a removal moved out of all of them (HIN-169): named in the
 * case, so moderation looks at those places as well. [location] is the key of the issue it stands
 * in, resolved once when the removal ran; null for a page or a person, whose title is not shown.
 */
public record OtherUse(ContentKind kind, String id, String parentId, String location) {

	/** Places a case names at most; a picture shared further than this is rare and the rest alike. */
	static final int MAX = 20;
}
