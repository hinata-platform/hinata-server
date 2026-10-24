package com.ahmadre.hinata.block;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One person blocking another (HIN-26, HIN-168). Private to the blocker: nobody is told, it is
 * not audited, and it ends contact rather than collaboration, so assigning and adding still
 * work.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("user_blocks")
@CompoundIndexes({
		// "Does A block B?" and what makes blocking twice one block.
		@CompoundIndex(name = "blocker_blocked", def = "{'blockerId': 1, 'blockedId': 1}", unique = true),
		// A's list, newest first, a page at a time.
		@CompoundIndex(name = "blocker_created", def = "{'blockerId': 1, 'createdAt': -1, '_id': -1}"),
		// Who blocks the person acting? Asked once per notification, answered from the index alone.
		@CompoundIndex(name = "blocked_blocker", def = "{'blockedId': 1, 'blockerId': 1}")
})
public class UserBlock {

	@Id
	private String id;
	private String blockerId;
	private String blockedId;
	private Instant createdAt;
}
