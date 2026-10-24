package com.ahmadre.hinata.moderation;

/** Who brought a case: a member, somebody without an account, the text check, or moderation itself. */
public enum ReportSource {
	MEMBER,
	PUBLIC,
	AUTOMATED,
	OWN_INITIATIVE
}
