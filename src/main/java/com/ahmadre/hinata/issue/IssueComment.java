package com.ahmadre.hinata.issue;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@Document("issue_comments")
public class IssueComment {

	/**
	 * A comment is either plain Markdown ({@code TEXT}) or a recorded voice
	 * message ({@code VOICE}). Legacy documents predate this field, so a missing
	 * value is read as {@link Type#TEXT} via {@link #resolvedType()}.
	 */
	public enum Type {
		TEXT, VOICE
	}

	/**
	 * Voice-message payload: the audio blob lives in object storage under the
	 * {@code voice/} prefix (streamed back through the API, never served
	 * directly). Duration and the pre-computed waveform peaks are stored inline
	 * so the client can render the bubble without decoding the audio itself.
	 */
	@Data
	@Builder
	public static class Voice {
		/** Object-storage key of the audio blob (bucket-internal). */
		private String objectKey;
		/** Playback length in milliseconds. */
		private int durationMs;
		/** Normalised amplitude peaks (0–100), one per waveform bar. */
		private List<Integer> peaks;
		/** Stored blob size in bytes. */
		private long size;
		/** Audio MIME type as recorded (e.g. {@code audio/mp4}, {@code audio/webm}). */
		private String contentType;
	}

	/**
	 * A single emoji reaction from one user. WhatsApp semantics: a user holds at
	 * most one reaction per comment (adding another replaces it, re-adding the
	 * same emoji removes it), enforced in the service — this list therefore has at
	 * most one entry per {@link #userId}.
	 */
	@Data
	@Builder
	public static class Reaction {
		/** The emoji grapheme (e.g. {@code ❤️}). */
		private String emoji;
		/** User who reacted. */
		private String userId;
		/** When the reaction was set (for stable ordering). */
		private Instant createdAt;
	}

	@Id
	private String id;

	@Indexed
	private String issueId;

	private String authorId;

	private Type type;

	/** Markdown for {@link Type#TEXT}; null/absent for voice comments. */
	private String text;

	/** Present only for {@link Type#VOICE}. */
	private Voice voice;

	/** Emoji reactions; at most one per user (WhatsApp-style). Never null on save. */
	@Builder.Default
	private List<Reaction> reactions = new java.util.ArrayList<>();

	/**
	 * Whether this comment is pinned to the top of the thread. Boxed {@link Boolean}
	 * (not primitive) so legacy documents predating this field — which read back as
	 * {@code null} — don't fail the all-args persistence constructor. Treated as
	 * {@code false} when null.
	 */
	@Builder.Default
	private Boolean pinned = false;

	/** When it was pinned (pin ordering); null when not pinned. */
	private Instant pinnedAt;

	/**
	 * When the text was last edited by its author. Distinct from {@link #updatedAt}
	 * (which @LastModifiedDate bumps on <em>any</em> save, incl. reactions/pins), so
	 * the "edited" marker only reflects real content edits.
	 */
	private Instant editedAt;

	/**
	 * The root comment this one is a reply to; null for top-level comments. Always
	 * normalised to the thread ROOT in the service (a reply to a reply points at the
	 * same root), so replies form a single flat thread. Indexed for reply counts and
	 * per-root reply fetches.
	 */
	@Indexed
	private String replyToId;

	/** Denormalised author of {@link #replyToId} so the quote renders without a lookup. */
	private String replyToAuthorId;

	/** Denormalised short preview of the quoted comment ("🎤"/"📷" for media). */
	private String replyToPreview;

	/**
	 * Number of replies whose (root-normalised) {@link #replyToId} points at this
	 * comment. Computed at read time for top-level comments and never persisted
	 * ({@link Transient}); null on replies and on writes.
	 */
	@Transient
	private Integer replyCount;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	/** Never-null type: legacy documents without the field are plain text. */
	public Type resolvedType() {
		return type != null ? type : Type.TEXT;
	}
}
