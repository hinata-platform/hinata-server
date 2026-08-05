package com.ahmadre.hinata.moderation.report;

import com.ahmadre.hinata.moderation.ModerationCategory;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One user's notice that a specific piece of content breaks the rules.
 *
 * <p>This is the human half of moderation. The automated gate
 * ({@link com.ahmadre.hinata.moderation.ModerationService}) only ever sees what a
 * classifier can score; a report is what a colleague noticed and the machine did
 * not — which is the case that actually matters, because a bug tracker's abuse is
 * mostly targeted at a person rather than lexically obvious.
 *
 * <p>The record is deliberately shaped by DSA Art. 16 (notice and action): it
 * carries who submitted it, an exact electronic location of the content
 * ({@link #targetType} + {@link #targetId}), the reason, an optional explanation,
 * and — via {@link #handledBy} / {@link #handledAt} / {@link #handlerNote} — the
 * decision that closed it, which Art. 17 requires be communicable as a statement
 * of reasons. It stores a <em>reference</em> to the content and never a copy, for
 * the same reason {@link com.ahmadre.hinata.moderation.ModerationRecorder} does
 * not: a second store of the worst material in the product serves nobody and would
 * have to be carried through every export and erasure path.
 */
@Data
@Builder
@Document("content_reports")
// The duplicate-suppression check (has this reporter already got an open report on
// this exact target?) runs on every submission, and the admin queue lists by state
// and recency. One compound serves the equality prefix of both without a scan.
@CompoundIndex(name = "reporter_target_state",
		def = "{'reporterId': 1, 'targetType': 1, 'targetId': 1, 'state': 1}")
@CompoundIndex(name = "state_created", def = "{'state': 1, 'createdAt': -1}")
public class ContentReport {

	/**
	 * What kind of thing was reported.
	 *
	 * <p>{@link #USER} is not a convenience alias for "the author of some content":
	 * Google Play rejects apps that can only report a message, so reporting the
	 * person — for a pattern of behaviour that no single comment carries — has to be
	 * its own first-class notice with no content id attached.
	 */
	public enum TargetType {
		ISSUE, COMMENT, ARTICLE, USER, ATTACHMENT
	}

	/**
	 * Why the reporter says the content breaks the rules.
	 *
	 * <p>Every constant except {@link #OTHER} maps onto a {@link ModerationCategory},
	 * so a human notice and a machine verdict speak the same vocabulary: the queue can
	 * sort them together, an admin sees one taxonomy rather than two, and the reason a
	 * user is eventually given for a removal reads identically whether the case started
	 * with a classifier or with a colleague.
	 *
	 * <p>{@link #OTHER} carries no category on purpose. Forcing every reporter to pick
	 * a policy label they do not know is how reports stop being filed at all; the free
	 * text in {@link ContentReport#note} is what the moderator reads in that case.
	 */
	public enum ReportReason {

		SEXUAL(ModerationCategory.SEXUAL),
		SEXUAL_MINORS(ModerationCategory.SEXUAL_MINORS),
		VIOLENCE(ModerationCategory.VIOLENCE),
		VIOLENT_THREAT(ModerationCategory.VIOLENT_THREAT),
		HATE(ModerationCategory.HATE),
		HARASSMENT(ModerationCategory.HARASSMENT),
		SELF_HARM(ModerationCategory.SELF_HARM),
		EXTREMISM(ModerationCategory.EXTREMISM),
		ILLEGAL(ModerationCategory.ILLEGAL),
		MALWARE(ModerationCategory.MALWARE),

		/**
		 * Unsolicited or promotional content.
		 *
		 * <p>Carries no category because nothing detects it: spam is not one of the
		 * policy categories the pipeline scores, and it never will be from a lexicon.
		 * It exists anyway because it is the reason people actually reach for first,
		 * and a picker that does not offer it collects "OTHER" with an empty note.
		 */
		SPAM(null),

		/** Something the categories do not cover; the note carries the substance. */
		OTHER(null);

		private final ModerationCategory category;

		ReportReason(ModerationCategory category) {
			this.category = category;
		}

		/** The policy category this reason claims, or {@code null} for {@link #OTHER}. */
		public ModerationCategory category() {
			return category;
		}

		/**
		 * Whether a report of this reason must reach a moderator immediately rather
		 * than waiting in the queue. Mirrors
		 * {@link com.ahmadre.hinata.moderation.ModerationPolicy#isOverridable}: the two
		 * categories no admin setting may weaken are also the two nobody should be able
		 * to leave sitting.
		 */
		public boolean urgent() {
			return category == ModerationCategory.SEXUAL_MINORS
					|| category == ModerationCategory.MALWARE;
		}
	}

	/** Where a report is in its lifecycle. */
	public enum State {

		/** Filed, nobody has judged it yet. */
		OPEN,

		/** A moderator agreed and acted (removed, hid, warned, deactivated). */
		ACTIONED,

		/** A moderator looked and found no violation. */
		DISMISSED
	}

	@Id
	private String id;

	/** Who filed it. Never anonymous — a report the reporter cannot be asked about
	 * is a report that cannot be weighed against a pattern of malicious reporting. */
	@Indexed
	private String reporterId;

	private TargetType targetType;

	/**
	 * Canonical id of the reported entity: the issue, comment, article, attachment or
	 * user. Always the resolved id and never the alias the client used, so two people
	 * reporting one issue — one by key, one by id — land on the same target.
	 */
	private String targetId;

	/**
	 * The issue a comment or an attachment belongs to; {@code null} for targets that
	 * stand on their own.
	 *
	 * <p>Required for an attachment, which is embedded in its issue and cannot be
	 * located by id alone. Kept for a comment as well, because the moderator has to
	 * open the thread to judge the comment — a single message read out of the
	 * conversation it answers is exactly how a reasonable remark looks like an
	 * unprovoked one.
	 */
	private String contextId;

	/**
	 * Owning project, when the target has one. Kept denormalised so the queue can be
	 * scoped to the projects a moderating lead actually oversees without joining back
	 * to content that may since have been deleted.
	 */
	private String projectId;

	private ReportReason reason;

	/**
	 * The reporter's own words.
	 *
	 * <p>This is user-authored text like any other and is put through the moderation
	 * gate before it is stored — a report form that skips the filter is simply a way
	 * to write whatever you like into a field an admin is guaranteed to read.
	 */
	private String note;

	@Builder.Default
	private State state = State.OPEN;

	@CreatedDate
	private Instant createdAt;

	/** Admin who closed it; {@code null} while {@link State#OPEN}. */
	private String handledBy;

	private Instant handledAt;

	/**
	 * The moderator's reasoning, written when the report is closed. This is the text
	 * a statement of reasons is built from (DSA Art. 17), so it is kept even when the
	 * reported content itself is gone.
	 */
	private String handlerNote;
}
