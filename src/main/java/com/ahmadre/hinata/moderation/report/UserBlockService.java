package com.ahmadre.hinata.moderation.report;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns who a user has blocked, and is the only place that question is answered.
 *
 * <p>Every read path that renders another person's writing — the comment thread,
 * the reply list, mention fan-out, notification delivery — is expected to filter
 * through {@link #blockedBy(String)} rather than reimplementing the rule. A block
 * that holds on the issue page but not in the push notification is not a block; the
 * user still gets the message, just somewhere they cannot mute it.
 *
 * <p>The two mutations are idempotent by design. A block is a boundary, not a
 * transaction: pressing "block" on a user who is already blocked must succeed
 * quietly rather than fail with a conflict, because the only thing the caller cares
 * about is the end state, and an error dialog there reads as "the block did not
 * work".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBlockService {

	private final UserBlockRepository blocks;
	private final UserRepository users;
	private final AuditService audit;

	/**
	 * The ids [userId] has blocked. Never {@code null}; empty for the overwhelming
	 * majority of accounts, which is what makes calling it per request affordable —
	 * the query is a single equality lookup on the {@code blocker_blocked} index over
	 * a collection that stays tiny in any real workspace.
	 */
	public Set<String> blockedBy(String userId) {
		if (userId == null) {
			return Set.of();
		}
		Set<String> ids = new LinkedHashSet<>();
		for (UserBlock block : blocks.findByBlockerIdOrderByCreatedAtDesc(userId)) {
			ids.add(block.getBlockedId());
		}
		return ids;
	}

	/**
	 * The ids who have blocked [userId] — the inverse of {@link #blockedBy(String)}.
	 *
	 * <p>Exists because the two enforcement points ask the question from opposite
	 * ends. A read knows the viewer and hides the authors they blocked; a
	 * notification fan-out knows only the author and has to drop the recipients who
	 * blocked <em>them</em>. Deriving the second from the first would mean one
	 * lookup per recipient inside the fan-out loop — so the inverse gets its own
	 * single query, called once per event and never per recipient.
	 *
	 * <p>Nothing here is ever shown to the blocked user: they must not be able to
	 * learn who blocked them, and this result stays inside the delivery layer.
	 */
	public Set<String> blockersOf(String userId) {
		if (userId == null) {
			return Set.of();
		}
		Set<String> ids = new LinkedHashSet<>();
		for (UserBlock block : blocks.findByBlockedId(userId)) {
			ids.add(block.getBlockerId());
		}
		return ids;
	}

	/**
	 * A block paired with the account it names. {@code user} is {@code null} when that
	 * account has since been deleted — the row is still shown rather than silently
	 * dropped, because a list that quietly shrinks makes a user wonder whether their
	 * block is still in force.
	 */
	public record BlockedUser(UserBlock block, User user) {
	}

	/**
	 * The blocks [userId] holds, newest first — what {@code /me/blocks} renders. The
	 * accounts are fetched in one batch rather than per row, so the list costs two
	 * queries however many blocks it holds.
	 */
	public List<BlockedUser> listFor(String userId) {
		List<UserBlock> rows = blocks.findByBlockerIdOrderByCreatedAtDesc(userId);
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<String, User> byId = new HashMap<>();
		users.findAllById(rows.stream().map(UserBlock::getBlockedId).toList())
				.forEach(user -> byId.put(user.getId(), user));
		return rows.stream()
				.map(block -> new BlockedUser(block, byId.get(block.getBlockedId())))
				.toList();
	}

	/**
	 * Blocks [blockedId] for [blocker], or does nothing if it is already blocked.
	 *
	 * @throws ApiException 400 when a user tries to block themselves, 404 when the
	 *                      target does not exist
	 */
	public BlockedUser block(User blocker, String blockedId) {
		String target = blockedId == null ? "" : blockedId.trim();
		if (target.isEmpty()) {
			throw ApiException.notFound("user");
		}
		if (target.equals(blocker.getId())) {
			// Not merely nonsensical: a self-block would filter the user out of their
			// own threads and leave them unable to see the replies to their own work,
			// with no obvious way back.
			throw ApiException.badRequest("error.block.self");
		}
		User blocked = users.findById(target).orElseThrow(() -> ApiException.notFound("user"));
		UserBlock row = blocks.findByBlockerIdAndBlockedId(blocker.getId(), blocked.getId())
				.orElseGet(() -> create(blocker, blocked));
		return new BlockedUser(row, blocked);
	}

	/** Lifts the block, or does nothing when there was none. */
	public void unblock(User blocker, String blockedId) {
		if (blockedId == null || blockedId.isBlank()) {
			return;
		}
		if (blocks.findByBlockerIdAndBlockedId(blocker.getId(), blockedId).isEmpty()) {
			return;
		}
		blocks.deleteByBlockerIdAndBlockedId(blocker.getId(), blockedId);
		audit.event(AuditAction.USER_UNBLOCKED)
				.actor(blocker)
				.target(blockedId, users.findById(blockedId).map(User::getDisplayName).orElse(null))
				.log();
	}

	/**
	 * Removes every block naming [userId] in either direction. Called when an account
	 * is erased, so the deletion does not leave rows pointing at a user who is gone.
	 */
	public void purgeFor(String userId) {
		if (userId != null) {
			blocks.deleteByBlockerIdOrBlockedId(userId, userId);
		}
	}

	private UserBlock create(User blocker, User blocked) {
		try {
			UserBlock saved = blocks.save(UserBlock.builder()
					.blockerId(blocker.getId())
					.blockedId(blocked.getId())
					.build());
			audit.event(AuditAction.USER_BLOCKED).actor(blocker).target(blocked).log();
			return saved;
		}
		catch (DuplicateKeyException race) {
			// Two devices pressed block at the same moment. The unique index did its
			// job; the end state is the one the caller asked for, so return it rather
			// than surfacing a conflict for an operation that is meant to be idempotent.
			return blocks.findByBlockerIdAndBlockedId(blocker.getId(), blocked.getId())
					.orElseThrow(() -> race);
		}
	}
}
