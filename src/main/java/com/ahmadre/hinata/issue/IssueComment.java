package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.content.ContentMark;
import com.ahmadre.hinata.content.ContentOrigin;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@Document("issue_comments")
// Thread reads filter by issueId (+ replyToId null for top-level) and sort by
// createdAt on every issue open; this compound satisfies filter+sort as an
// IXSCAN instead of an in-memory sort.
@CompoundIndex(name = "issue_reply_created", def = "{'issueId': 1, 'replyToId': 1, 'createdAt': -1}")
// The global search (HIN-165) walks comments newest first and stops once enough of them sit on
// issues the caller may open; without this it sorted every matching comment in memory first.
@CompoundIndex(name = "created_id", def = "{'createdAt': -1, '_id': -1}")
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

	/**
	 * How the comment came in, when not from its author's own hand: {@code GIT} for a smart
	 * commit, which stands under whoever connected the repository (HIN-169). Absent for a member.
	 */
	@JsonIgnore
	private ContentOrigin origin;

	private Type type;

	/**
	 * Plain text of {@link #textDoc} for {@link Type#TEXT}; null/absent for voice
	 * comments. Derived on every write by
	 * {@link com.ahmadre.hinata.richtext.RichTextService} — never set directly.
	 */
	private String text;

	/**
	 * The Lexical document — the source of truth for a text comment. Clients get it in two forms
	 * (HIN-181), as an issue's description: {@code textDoc} the legacy projection, and
	 * {@code textLayoutDoc} the full document when it differs.
	 */
	@lombok.Getter(lombok.AccessLevel.NONE)
	private String textDoc;

	/** Both client forms of {@link #textDoc}, drawn once per response. Not persisted. */
	@Transient
	@JsonIgnore
	@lombok.Getter(lombok.AccessLevel.NONE)
	@lombok.Setter(lombok.AccessLevel.NONE)
	@lombok.EqualsAndHashCode.Exclude
	@lombok.ToString.Exclude
	private com.ahmadre.hinata.richtext.LegacyDocs.Memo textForms;

	/** The stored document; see {@link #textDocForClients()} for what clients get. */
	@JsonIgnore
	public String getTextDoc() {
		return textDoc;
	}

	/** The comment as {@code textDoc} goes out: readable by an app published before HIN-181. */
	@com.fasterxml.jackson.annotation.JsonProperty("textDoc")
	public String textDocForClients() {
		return forms().legacy(textDoc);
	}

	/** The full document when it differs from {@code textDoc}, else null (HIN-181). */
	@com.fasterxml.jackson.annotation.JsonProperty("textLayoutDoc")
	public String textLayoutDoc() {
		return forms().layout(textDoc);
	}

	private com.ahmadre.hinata.richtext.LegacyDocs.Memo forms() {
		if (textForms == null) textForms = new com.ahmadre.hinata.richtext.LegacyDocs.Memo();
		return textForms;
	}

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
	 * The comment actually quoted, which {@link #replyToId} is not once a reply answers a reply.
	 * What finds the quote when the quoted comment is removed or deleted (HIN-169); absent on
	 * replies written before it existed, which are found by their preview instead.
	 */
	@Indexed(sparse = true)
	private String replyToTargetId;

	/** What moderation removed, under {@code text} (HIN-169); absent otherwise. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Map<String, ContentMark> moderation;

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
