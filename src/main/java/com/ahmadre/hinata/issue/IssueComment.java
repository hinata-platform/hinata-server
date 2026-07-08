package com.ahmadre.hinata.issue;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
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

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	/** Never-null type: legacy documents without the field are plain text. */
	public Type resolvedType() {
		return type != null ? type : Type.TEXT;
	}
}
