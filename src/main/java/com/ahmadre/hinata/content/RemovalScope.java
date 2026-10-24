package com.ahmadre.hinata.content;

/**
 * How much of a piece of content a removal takes: all of it, one field of an issue, a page back
 * to the version before the reported one, a person's picture or the words on their profile.
 * Which scopes a kind allows is the surface's to say ({@link ContentSurface#scopes()}).
 */
public enum RemovalScope {
	WHOLE,
	TITLE,
	DESCRIPTION,
	REVERT,
	AVATAR,
	PROFILE_TEXT
}
