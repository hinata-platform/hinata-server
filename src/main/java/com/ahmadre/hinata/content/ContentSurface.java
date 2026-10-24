package com.ahmadre.hinata.content;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;

import java.util.Optional;
import java.util.Set;

/**
 * One kind of content as moderation reaches it (HIN-169): issue, comment, attachment, page or
 * person. Implemented by the package that owns the content, called by the moderation module.
 *
 * <p>Removing rewrites the content in place: the field becomes the placeholder and carries a
 * {@link ContentMark}, so every way it is read shows the placeholder without knowing anything
 * about moderation. The original goes to the {@link Quarantine} first and is deleted last, so
 * there is never a moment without either.
 */
public interface ContentSurface {

	ContentKind kind();

	/** The scopes a removal of this kind may take. */
	Set<RemovalScope> scopes();

	/** Refuses a scope this kind does not know (400 {@code error.moderation.scopeNotAllowed}). */
	default void requireScope(RemovalScope scope) {
		if (scope == null || !scopes().contains(scope)) {
			throw ApiException.badRequest("error.moderation.scopeNotAllowed");
		}
	}

	/** The content, if [viewer] may see it; empty otherwise, which callers answer with 404. */
	Optional<ContentSnapshot> snapshot(TargetRef target, User viewer);

	/** The content without asking who looks. For the moderation module only ({@code ModuleBoundaryTest}). */
	Optional<ContentSnapshot> forModeration(TargetRef target);

	/**
	 * Removes [scope] of the content for [caseId]. [category] is what the case was decided on: an
	 * image another document embeds as well is copied into the quarantine and left for that
	 * document, unless the category is sensitive, when it is moved out of all of them.
	 *
	 * <p>Throws when the content is not there (404) or when an object cannot be copied into the
	 * quarantine, in which case nothing was rewritten. Removing what is already removed takes
	 * nothing and answers an empty removal.
	 */
	Removal remove(TargetRef target, RemovalScope scope, String caseId, ReportCategory category,
			Placeholders placeholders);

	/**
	 * Puts back what [removal] took, unless the content was written anew since or is gone. The
	 * quarantine keeps its copy for as long as the case does.
	 */
	RestoreOutcome restore(Removal removal);
}
