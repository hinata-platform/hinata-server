package com.ahmadre.hinata.moderation.report;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One person's decision to stop seeing another person.
 *
 * <p><b>This is not a ban.</b> Nothing here deactivates an account, hides content
 * from anyone else, or tells the blocked user they were blocked — it is a filter
 * applied to the blocker's own reads. That distinction is the whole point: a ban is
 * an organisational judgement an admin makes and must be able to justify, while a
 * block is a personal boundary that needs no justification and no approval, and
 * conflating the two would mean either that nobody can protect themselves without
 * an admin, or that any user can silence a colleague for everyone. Apple Guideline
 * 1.2 asks for the second thing to exist alongside reporting; only this file
 * provides it.
 *
 * <p>Blocking is deliberately one-directional and cheap to undo. It also does
 * <em>not</em> remove the blocked user from shared work: an issue they are assigned
 * to still exists, still shows its assignee, and still moves across the board. What
 * disappears for the blocker is the discretionary, person-to-person surface —
 * comments, replies and mentions. Hiding the work itself would let a block quietly
 * corrupt the blocker's view of the project's state, which is the one thing a
 * tracker must never do.
 */
@Data
@Builder
@Document("user_blocks")
// Unique so a double POST (or two devices racing) cannot create a second row, and
// so the per-request "who have I blocked?" lookup is answered from the index prefix
// rather than by scanning. The pair is the identity of the block.
@CompoundIndex(name = "blocker_blocked", def = "{'blockerId': 1, 'blockedId': 1}", unique = true)
public class UserBlock {

	@Id
	private String id;

	/** The user who no longer wants to see {@link #blockedId}'s writing. */
	private String blockerId;

	/**
	 * The user being filtered out. Never notified, never told.
	 *
	 * <p>Indexed on its own even though it is the second half of
	 * {@code blocker_blocked}: the notification fan-out searches by this field alone
	 * ("who does this author's comment not reach?"), which an index whose prefix is
	 * {@code blockerId} cannot serve.
	 */
	@Indexed
	private String blockedId;

	@CreatedDate
	private Instant createdAt;
}
