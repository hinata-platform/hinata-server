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

	/** Looks at content without the sight check from outside the moderation module. */
	public static class AsksForModeration {
		Object look(ContentSurface surface) {
			return surface.forModeration(TargetRef.of(ContentKind.COMMENT, "c1"));
		}
	}
}
