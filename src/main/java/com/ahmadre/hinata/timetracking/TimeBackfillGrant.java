package com.ahmadre.hinata.timetracking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Days an administrator has opened for <em>one</em> person to record (HIN-89, R9).
 *
 * <p>The answer to "I have to enter a year of parental leave" or "my entries for the
 * closed month are missing". A lock exception would open those days for everyone on
 * the instance and publish its reason — a sentence about a person — to everybody who
 * reads the rules. A grant opens them for the person who asked, keeps the reason
 * between that person and the administrators, and ends by itself.
 *
 * <p>It lifts the two limits that are about <em>when</em>: {@code maxDaysBack} and the
 * lock date. It does not lift a submitted or approved period; changing hours that
 * somebody has signed off stays a reopen, which is the approver's act.
 *
 * <p>Expired grants are removed by Mongo itself ({@link #expiresAt} carries a TTL
 * index); the audit log keeps the record that one existed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("time_backfill_grants")
@CompoundIndex(name = "user_span", def = "{'userId': 1, 'to': 1, 'from': 1}")
public class TimeBackfillGrant {

	@Id
	private String id;

	private String userId;

	/** First day opened, inclusive. */
	private LocalDate from;

	/** Last day opened, inclusive. */
	private LocalDate to;

	/** The administrator's reason. Shown to the person and to administrators only. */
	private String note;

	private String grantedBy;

	private Instant grantedAt;

	/** When the grant stops applying; Mongo removes the document shortly after. */
	@Indexed(expireAfter = "0s")
	private Instant expiresAt;

	/** The request it answered, when there was one. */
	private String requestId;
}
