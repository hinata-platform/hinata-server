package com.ahmadre.hinata.content;

import com.ahmadre.hinata.issue.Issue;

/**
 * A class written to break the content boundary (HIN-26): the vocabulary every surface
 * implements may not know any of the surfaces. Imported as bytecode by
 * {@code ModuleBoundaryTest}, never used.
 */
@SuppressWarnings("unused")
public final class ContentBoundaryProbes {

	private ContentBoundaryProbes() {
	}

	/** Knows the issue, which content may not. */
	public static class KnowsAnIssue {
		private Issue issue;
	}
}
