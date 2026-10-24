package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.RestoreOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Set;

/**
 * Words the statement of reasons (HIN-171) from a case and its decision, in one language and one
 * time zone. The one source of its wording: the notice, its mail and the preview a moderator
 * sees before deciding all come from here, so what the person reads is what was previewed.
 *
 * <p>Nothing here names a reporter, and nothing repeats the removed words: the measure names the
 * kind of content, when it was written and the issue it stands in, never a title.
 */
@Component
@RequiredArgsConstructor
public class Statements {

	/** The house rules a decision applied; {@code ?version=N} once there are versions (HIN-175). */
	static final String RULES_PATH = "/api/v1/public/rules";

	/** Kinds whose location is an issue's key, safe to name; a page's location is its title. */
	private static final Set<ContentKind> LOCATED = Set.of(ContentKind.ISSUE, ContentKind.COMMENT,
			ContentKind.ATTACHMENT);

	private final MessageSource messages;

	/**
	 * The statement for [c] and its [decision] as of [now], for a reader in [locale] and [zone].
	 * [canSignIn] says whether they appeal in the app or by replying to the mail.
	 */
	public Statement of(ModerationCase c, Decision decision, Locale locale, ZoneId zone, boolean canSignIn,
			Instant now) {
		Instant appealUntil = Retention.appealUntil(decision.decidedAt());
		Appeal appeal = c.getAppeal();
		boolean appealable = appeal == null && c.getRestoration() == null && !now.isAfter(appealUntil);
		return new Statement(
				c.getId(),
				text(locale, "moderation.statement.headline"),
				measure(c, decision, locale, zone),
				decision.removal(),
				null,
				text(locale, "moderation.statement.basis." + decision.basis()),
				decision.facts(),
				Boolean.TRUE.equals(decision.automatedDetection()),
				false,
				text(locale, Boolean.TRUE.equals(decision.automatedDetection())
						? "moderation.statement.automation.detected" : "moderation.statement.automation.person"),
				decision.ground(),
				groundText(decision, locale),
				decision.ruleReference(),
				decision.lawReference(),
				decision.rulesVersion() == null ? RULES_PATH : RULES_PATH + "?version=" + decision.rulesVersion(),
				appealUntil,
				appealable,
				!canSignIn,
				appealText(c, appealable, canSignIn, appealUntil, locale, zone),
				appeal == null ? null : appeal.state(),
				text(locale, "moderation.statement.courts"),
				decision.messageToAuthor());
	}

	/** What was done to which content, from when; for content with no account behind it, neutral. */
	String measure(ModerationCase c, Decision decision, Locale locale, ZoneId zone) {
		ContentSnapshot evidence = c.getEvidence();
		ContentKind kind = c.getTarget() == null ? ContentKind.OTHER : c.getTarget().kind();
		Instant written = evidence == null ? null
				: evidence.editedAt() != null ? evidence.editedAt() : evidence.createdAt();
		String when = written == null ? "" : dateTime(written, locale, zone);
		String where = LOCATED.contains(kind) && evidence != null && evidence.location() != null ? evidence.location() : "";
		String key = "moderation.statement.measure." + kind + "." + decision.removal();
		return messages.getMessage(key, new Object[] { when, where },
				text(locale, "moderation.statement.measure.other"), locale);
	}

	String groundText(Decision decision, Locale locale) {
		if (decision.ground() == null) {
			return null;
		}
		String reference = decision.ground() == Decision.Ground.LAW ? decision.lawReference() : decision.ruleReference();
		return messages.getMessage("moderation.statement.ground." + decision.ground(), new Object[] { reference },
				locale);
	}

	private String appealText(ModerationCase c, boolean appealable, boolean canSignIn, Instant appealUntil,
			Locale locale, ZoneId zone) {
		Appeal appeal = c.getAppeal();
		Restoration restoration = c.getRestoration();
		if (appeal != null) {
			return switch (appeal.state()) {
				case PENDING -> messages.getMessage("moderation.statement.appeal.pending",
						new Object[] { date(appeal.filedAt(), locale, zone) }, locale);
				case UPHELD -> text(locale, "moderation.statement.appeal.upheld");
				case REVERSED -> text(locale, "moderation.statement.appeal.reversed." + outcome(appeal.restored()));
			};
		}
		if (restoration != null) {
			return text(locale, "moderation.statement.restored." + outcome(restoration.outcome()));
		}
		String until = date(appealUntil, locale, zone);
		if (!appealable) {
			return messages.getMessage("moderation.statement.appeal.closed", new Object[] { until }, locale);
		}
		return messages.getMessage(canSignIn ? "moderation.statement.appeal.app" : "moderation.statement.appeal.reply",
				new Object[] { until }, locale);
	}

	private static RestoreOutcome outcome(RestoreOutcome outcome) {
		return outcome == null ? RestoreOutcome.RESTORED : outcome;
	}

	private String text(Locale locale, String key) {
		return messages.getMessage(key, null, locale);
	}

	static String dateTime(Instant at, Locale locale, ZoneId zone) {
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
				.withZone(zone).format(at);
	}

	static String date(Instant at, Locale locale, ZoneId zone) {
		return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).withZone(zone).format(at);
	}
}
