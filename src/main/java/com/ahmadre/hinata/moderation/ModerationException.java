package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import lombok.Getter;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;

/**
 * Thrown when content is refused, carrying the statement of reasons.
 *
 * <p>The message is assembled from two localized parts — a template and the
 * category's own reason — so the author is told <em>which</em> rule their content
 * fell under rather than a generic "not allowed". That specificity is not only
 * better UX: for a hosting service, DSA Art. 17(3) requires the facts relied on,
 * whether automated means were used and how to seek redress, and a message that
 * says nothing satisfies none of it.
 *
 * <p>What is deliberately <em>not</em> disclosed is the matched term and the score.
 * Telling an author "the word X at confidence 87" turns the filter into an oracle
 * they can iterate against until they find a phrasing that passes; the category
 * plus a route to a human is the accepted balance between explaining a decision and
 * publishing the ruleset. The precise evidence is kept on the moderation record for
 * the reviewing admin.
 *
 * <p>Status is 422 rather than 400: the request was well-formed and understood, it
 * was the content that was refused. That distinction matters to the client, which
 * shows a policy dialog for a refusal and a field error for a malformed body.
 */
@Getter
public class ModerationException extends ApiException {

	/** HTTP status used for every content refusal. */
	public static final HttpStatus STATUS = HttpStatus.UNPROCESSABLE_ENTITY;

	private final transient ModerationVerdict verdict;
	private final ModerationSurface surface;

	private ModerationException(String messageKey, Object[] args,
			ModerationVerdict verdict, ModerationSurface surface) {
		super(STATUS, messageKey, args);
		// Redacted at construction rather than at the call sites: this object is the
		// one that travels to the author, and an exception that has to be remembered
		// to be sanitised eventually reaches somewhere it was not.
		this.verdict = verdict == null ? null : verdict.redacted();
		this.surface = surface;
	}

	/**
	 * A refusal naming [verdict]'s primary category.
	 *
	 * <p>The category's reason is passed as a {@link DefaultMessageSourceResolvable}
	 * so it is resolved in the request's own locale and substituted into the
	 * template — the same mechanism {@link ApiException#notFound} uses for entity
	 * names, which keeps one localization path in the product rather than two.
	 */
	public static ModerationException blocked(ModerationVerdict verdict, ModerationSurface surface) {
		ModerationCategory category = verdict.primaryCategory();
		if (category == null) {
			return new ModerationException("error.moderation.blocked", new Object[0], verdict, surface);
		}
		return new ModerationException("error.moderation.blockedReason",
				new Object[] { new DefaultMessageSourceResolvable(category.reasonKey()) },
				verdict, surface);
	}

	/** A refusal of an uploaded file, which names the file as well as the reason. */
	public static ModerationException blockedFile(ModerationVerdict verdict, ModerationSurface surface,
			String fileName) {
		ModerationCategory category = verdict.primaryCategory();
		Object reason = category != null
				? new DefaultMessageSourceResolvable(category.reasonKey())
				: new DefaultMessageSourceResolvable("moderation.reason.generic");
		return new ModerationException("error.moderation.blockedFile",
				new Object[] { fileName == null ? "" : fileName, reason }, verdict, surface);
	}

	/**
	 * A refusal because a required check could not run and the surface is not
	 * allowed to fail open — inbound e-mail and proxied external images, where
	 * nobody is waiting on the response and nobody is accountable for the content.
	 */
	public static ModerationException unavailable(ModerationSurface surface) {
		return new ModerationException("error.moderation.unavailable", new Object[0],
				ModerationVerdict.allow(ModerationVerdict.ModerationTier.DISABLED).degrade(), surface);
	}
}
