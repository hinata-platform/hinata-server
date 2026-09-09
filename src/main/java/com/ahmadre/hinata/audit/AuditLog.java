package com.ahmadre.hinata.audit;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * One immutable security-audit record. Written by {@link AuditService} whenever a
 * recorded {@link AuditAction} occurs and the action is enabled in the runtime
 * audit settings. Never updated after creation.
 */
@Data
@Builder
@Document("audit_log")
// The history of one time entry, newest first. A partial index, because the key
// exists on a small minority of records and an index over every row for the sake
// of them would be paid for by every write in the system. Named after what it
// answers rather than after the module, so the next feature that hangs a history
// off an object id reuses it instead of adding a second.
// `_id` is in the key pattern because it is in the sort: without it the sort
// spec is not a prefix of the index and Mongo falls back to an in-memory sort of
// every record about that entry.
@CompoundIndex(name = "work_item_history",
		def = "{'metadata.workItem': 1, 'timestamp': -1, '_id': -1}",
		partialFilter = "{'metadata.workItem': {$exists: true}}")
public class AuditLog {

	public enum Outcome {
		SUCCESS, FAILURE
	}

	@Id
	private String id;

	@Indexed
	@CreatedDate
	private Instant timestamp;

	@Indexed
	private AuditAction action;

	@Indexed
	private AuditCategory category;

	private AuditSeverity severity;

	private Outcome outcome;

	/** Id of the user who performed the action, when known (null for anonymous). */
	@Indexed
	private String actorId;

	/** Human label for the actor — display name, or the identifier they typed. */
	private String actorLabel;

	/** Id of the object the action targeted (another user, a setting…), if any. */
	private String targetId;

	/** Human label for the target — e.g. the affected user's name. */
	private String targetLabel;

	/** Masked client IP (last octets hidden) the action originated from. */
	private String ip;

	/** Best-effort client/device string from the User-Agent. */
	private String userAgent;

	/** Optional structured detail (old → new role, lockout minutes, …). */
	private Map<String, String> metadata;
}
