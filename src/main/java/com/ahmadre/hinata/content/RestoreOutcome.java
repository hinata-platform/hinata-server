package com.ahmadre.hinata.content;

/** What restoring removed content came to. */
public enum RestoreOutcome {
	RESTORED,
	/** Somebody wrote it anew after the removal; their text stays. */
	REWRITTEN_SINCE,
	/** It no longer exists. */
	GONE
}
