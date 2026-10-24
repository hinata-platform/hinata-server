package com.ahmadre.hinata.block;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who blocks whom (HIN-26, HIN-168). In the core rather than in moderation: blocking needs no
 * moderator, and notifications, sharing and participants ask it.
 *
 * <p>Blocking ends contact, not collaboration. What it stops is decided where the contact
 * happens, by asking {@link #blocks} or {@link #blockersOf}; nothing here tells the blocked
 * person, and no answer anywhere may look different from "no access".
 */
@Service
@RequiredArgsConstructor
public class BlockService {

	/** Keeps {@link #blockedIds} a small answer from the index (about 30 KB). */
	public static final int MAX_BLOCKS = 1000;

	static final int MAX_PAGE = 100;

	private static final String COLLECTION = "user_blocks";

	private final UserBlockRepository rows;
	private final UserRepository users;
	private final MongoTemplate mongo;

	/** A blocked person as the blocker's list shows them. */
	public record BlockedPerson(String id, String displayName, String avatarUrl, Instant blockedAt) {
	}

	public record BlockPage(List<BlockedPerson> items, long total) {
	}

	/** Whether [blockerId] blocks [blockedId]. */
	public boolean blocks(String blockerId, String blockedId) {
		return blockerId != null && blockedId != null && rows.existsByBlockerIdAndBlockedId(blockerId, blockedId);
	}

	/** Everyone who blocks [actorId]: usually nobody, and read from the index alone. */
	public Set<String> blockersOf(String actorId) {
		if (actorId == null) {
			return Set.of();
		}
		Query query = Query.query(Criteria.where("blockedId").is(actorId));
		query.fields().include("blockerId").exclude("_id");
		return mongo.find(query, Document.class, COLLECTION).stream()
				.map(row -> row.getString("blockerId"))
				.collect(Collectors.toCollection(HashSet::new));
	}

	/** Blocks [blockedId] for [blocker]. Blocking again changes nothing. */
	public void block(User blocker, String blockedId) {
		if (blocker.getId().equals(blockedId)) {
			throw ApiException.badRequest("error.block.self");
		}
		if (blockedId == null || !users.existsById(blockedId)) {
			throw ApiException.notFound("user");
		}
		if (rows.existsByBlockerIdAndBlockedId(blocker.getId(), blockedId)) {
			return;
		}
		if (rows.countByBlockerId(blocker.getId()) >= MAX_BLOCKS) {
			throw ApiException.badRequest("error.block.limit", MAX_BLOCKS);
		}
		try {
			rows.insert(UserBlock.builder().blockerId(blocker.getId()).blockedId(blockedId)
					.createdAt(Instant.now()).build());
		} catch (DuplicateKeyException sameBlockAMomentAgo) {
			// Two taps at once: the unique index keeps it one block.
		}
	}

	/** Lifts the block. Lifting one that is not there is no error. */
	public void unblock(User blocker, String blockedId) {
		rows.deleteByBlockerIdAndBlockedId(blocker.getId(), blockedId);
	}

	/** [blocker]'s list, newest first; at most {@link #MAX_PAGE} to a page. */
	public BlockPage list(User blocker, int page, int size) {
		return list(blocker, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE)));
	}

	private BlockPage list(User blocker, PageRequest request) {
		Page<UserBlock> found = rows.findByBlockerIdOrderByCreatedAtDescIdDesc(blocker.getId(), request);
		Map<String, User> people = users.findAllById(found.map(UserBlock::getBlockedId).toList()).stream()
				.collect(Collectors.toMap(User::getId, Function.identity()));
		List<BlockedPerson> items = found.stream()
				.map(row -> {
					User person = people.get(row.getBlockedId());
					return new BlockedPerson(row.getBlockedId(), person == null ? null : person.getDisplayName(),
							person == null ? null : person.getAvatarUrl(), row.getCreatedAt());
				})
				.toList();
		return new BlockPage(items, found.getTotalElements());
	}

	/** Everyone [blocker] blocks, newest first: at most {@link #MAX_BLOCKS}, for their data export. */
	public List<BlockedPerson> everyBlockOf(User blocker) {
		return list(blocker, PageRequest.of(0, MAX_BLOCKS)).items();
	}

	/** Every id [blocker] blocks, for folding their posts away in the app. */
	public List<String> blockedIds(User blocker) {
		Query query = Query.query(Criteria.where("blockerId").is(blocker.getId()));
		query.fields().include("blockedId").exclude("_id");
		return mongo.find(query, Document.class, COLLECTION).stream()
				.map(row -> row.getString("blockedId"))
				.toList();
	}

	/** Removes [userId]'s blocks in both directions, for a deleted account. */
	long eraseEveryBlockOf(String userId) {
		return rows.deleteByBlockerIdOrBlockedId(userId, userId);
	}
}
