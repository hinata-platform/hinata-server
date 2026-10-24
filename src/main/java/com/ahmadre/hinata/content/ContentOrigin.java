package com.ahmadre.hinata.content;

/**
 * How content came in. {@link #EMAIL} and {@link #GIT} cannot be verified: a sender header can be
 * forged, and a smart commit stands under whoever connected the repository. For them the person the
 * content is filed under is never treated as its author when suspending or weighing a conflict.
 * {@link #MCP} was written with somebody's personal access token, in their name, and is theirs.
 */
public enum ContentOrigin {
	MEMBER,
	EMAIL,
	GIT,
	MCP
}
