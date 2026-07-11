package com.ahmadre.hinata.mailingest;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One managed e-mail-to-ticket connection: an IMAP mailbox/folder that is
 * polled and whose unseen messages become issues in the linked project.
 * Multiple connections may exist, so several mailboxes/folders can feed
 * different projects independently.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("ingest_connections")
public class IngestConnection {

	@Id
	private String id;

	/** Optional display name; the UI falls back to username/folder. */
	private String name;

	private boolean enabled;

	private String host;

	@Builder.Default
	private int port = 993;

	@Builder.Default
	private boolean ssl = true;

	private String username;

	/** Accepted on write, never echoed back to clients. */
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private String password;

	@Builder.Default
	private String folder = "INBOX";

	/** Project that receives the inbound issues. */
	private String projectId;

	@Builder.Default
	private int pollSeconds = 60;

	/** Derived, read-only: whether a password is stored for this connection. */
	@Transient
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private Boolean passwordSet;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;
}
