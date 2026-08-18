package com.ahmadre.hinata.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One recipient's pending bundle of changes to one issue.
 *
 * <p>Watching a busy issue means a burst of small edits — a status, a due date,
 * a label, an assignee — often within a minute of each other. The bell and the
 * push are immediate because they are cheap to glance past; a mail per edit is
 * not, and a mailbox that is punished for subscribing teaches people to
 * unsubscribe. So the e-mail (and only the e-mail, and only for watchers) waits
 * in here until the editing has settled.
 *
 * <p>The bundling is enforced by the unique partial index, not by the service:
 * two concurrent edits racing to open a bundle must not produce two mails, and
 * an index is the only place that can promise that across instances. Once
 * {@link #sentAt} is set the row leaves the partial index, so the next change
 * opens a fresh bundle.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("issue_mail_digests")
// At most ONE open bundle per (recipient, issue). The partial filter is what
// makes this work as a queue: sent rows drop out of the index and stop
// competing with the bundle that is currently filling up.
@CompoundIndex(name = "user_issue_open", def = "{'userId': 1, 'issueId': 1, 'sentAt': 1}",
		unique = true, partialFilter = "{'sentAt': {$eq: null}}")
// The sweep's two selections. Both are equality on sentAt followed by the very
// field they sort on, so each runs as an IXSCAN whose order Mongo takes straight
// from the index — no $or, and above all no blocking in-memory SORT, which is
// what an instance coming back from a day of downtime would otherwise hit with
// tens of thousands of pending rows (and, with fat `changes` arrays, the 32 MB
// sort limit that aborts the whole sweep).
@CompoundIndex(name = "due_soft", def = "{'sentAt': 1, 'softDueAt': 1}")
@CompoundIndex(name = "due_hard", def = "{'sentAt': 1, 'hardDueAt': 1}")
public class IssueMailDigest {

	@Id
	private String id;

	@Indexed
	private String userId;

	@Indexed
	private String issueId;

	/**
	 * Denormalized so the send-time access re-check costs one project read and
	 * needs no issue load to find out which project to ask about. The mail can go
	 * out up to half an hour after the change; by then the recipient may have been
	 * removed from the project.
	 */
	private String projectId;

	/** Appended to, never rewritten — see {@code IssueDigestService.queue}. */
	@Builder.Default
	private List<FieldChange> changes = new ArrayList<>();

	/** When this bundle opened. Kept for diagnostics; the ceiling itself is
	 *  {@link #hardDueAt}, computed from this once, at insert. */
	private Instant firstQueuedAt;

	/** When the last change landed. Kept for diagnostics; the debounce itself is
	 *  {@link #softDueAt}, recomputed from this on every change. */
	private Instant lastQueuedAt;

	/**
	 * {@code lastQueuedAt + quiet-window} — the moment the debounce says "the
	 * editing has settled, send it". Rewritten on every change.
	 *
	 * <p>Stored rather than derived in the query because a sweep asking
	 * {@code lastQueuedAt <= now - quietWindow} can only ever be one half of an
	 * {@code $or} with the ceiling, and an {@code $or} over two different fields
	 * cannot be served by one index — nor can it provide the sort. Moving the
	 * arithmetic to write time turns the read into a plain range scan on an
	 * indexed field.
	 *
	 * <p>The window is a property, so changing it only takes effect for changes
	 * queued afterwards; bundles already open keep the deadline they were given.
	 * That is the honest behaviour for a debounce and it needs no migration.
	 */
	private Instant softDueAt;

	/**
	 * {@code firstQueuedAt + max-delay} — the ceiling, written once at insert so
	 * continuous editing cannot push it out. Whichever of the two deadlines comes
	 * first wins; both are swept, so neither has to know about the other.
	 */
	private Instant hardDueAt;

	/**
	 * Set — atomically, and only by the instance that wins the claim — the moment
	 * the mail is committed to. Same idea as {@code Issue.dueReminderFor}: the
	 * marker is the idempotency, so a restart mid-send cannot double-mail.
	 *
	 * <p>It doubles as the expiry: a sent row is a tombstone, kept only long
	 * enough to answer "did this go out?" while someone is still asking. A TTL
	 * index on a nullable field ignores documents where the field is null, so the
	 * bundle that is currently filling up is never touched by it — which is
	 * exactly the semantics wanted, and the reason this and not {@code createdAt}
	 * carries the expiry.
	 */
	@Indexed(expireAfter = "P7D")
	private Instant sentAt;

	@CreatedDate
	private Instant createdAt;
}
