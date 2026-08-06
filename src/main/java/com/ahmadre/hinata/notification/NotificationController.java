package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationRepository notifications;
	private final CurrentUser currentUser;
	private final MongoTemplate mongo;
	// Every path that returns a body goes through this. A notification persists a
	// verbatim preview of somebody's comment, and a row written before a freeze
	// would otherwise keep serving that text from here forever.
	private final NotificationRedactor redactor;

	/**
	 * Client-facing notification shape. Decouples the HTTP contract from the
	 * {@code @Document} entity (layered-architecture rule) while remaining a
	 * byte-for-byte match of the entity's current JSON, so no client change is
	 * required. Enum {@code type} serializes to its {@code name()} exactly as the
	 * entity did.
	 */
	public record NotificationResponse(String id, String userId, Notification.Type type,
			String title, String body, String link, boolean read, java.time.Instant createdAt) {

		public static NotificationResponse from(Notification n) {
			return new NotificationResponse(n.getId(), n.getUserId(), n.getType(), n.getTitle(),
					n.getBody(), n.getLink(), n.isRead(), n.getCreatedAt());
		}
	}

	@GetMapping
	public Page<NotificationResponse> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		Page<Notification> found = notifications.findByUserIdOrderByCreatedAtDesc(
				currentUser.requireId(), PageRequest.of(page, Math.min(size, 100)));
		// Redacted, never re-counted: a frozen source withholds the body and keeps the
		// row, so the pager stays honest and the recipient still sees that something
		// happened. That is the opposite trade from the list endpoints, and it is the
		// right one here — this page is the reader's own history, and silently losing
		// entries from it looks like the product forgetting rather than withholding.
		redactor.redact(found.getContent());
		return found.map(NotificationResponse::from);
	}

	@GetMapping("/unread-count")
	public Map<String, Long> unreadCount() {
		return Map.of("count", notifications.countByUserIdAndReadFalse(currentUser.requireId()));
	}

	@PostMapping("/{id}/read")
	public NotificationResponse markRead(@PathVariable String id) {
		return NotificationResponse.from(setRead(id, true));
	}

	@PostMapping("/{id}/unread")
	public NotificationResponse markUnread(@PathVariable String id) {
		return NotificationResponse.from(setRead(id, false));
	}

	/**
	 * Saves the read flag and returns the row as the caller may see it.
	 *
	 * <p>Redacted AFTER the save, which is the whole reason this is one method and
	 * not two copies: the redaction is a view of the row, not an edit of it, and
	 * saving a redacted entity would write the withheld string into the database
	 * where releasing the freeze could never bring the text back.
	 */
	private Notification setRead(String id, boolean read) {
		Notification notification = owned(id);
		notification.setRead(read);
		Notification saved = notifications.save(notification);
		redactor.redact(saved);
		return saved;
	}

	/**
	 * Marks every one of the caller's unread notifications read in a single scoped
	 * bulk update — replaces the client fanning out one POST + DB write per id.
	 * The query is always constrained to the caller's own userId, so it can never
	 * touch another user's notifications.
	 */
	@PostMapping("/read-all")
	public Map<String, Long> markAllRead() {
		String userId = currentUser.requireId();
		long updated = mongo.updateMulti(
				Query.query(Criteria.where("userId").is(userId).and("read").is(false)),
				new Update().set("read", true),
				Notification.class).getModifiedCount();
		return Map.of("updated", updated);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		notifications.delete(owned(id));
	}

	/** The caller's own notification, or 404 — never another user's. */
	private Notification owned(String id) {
		String userId = currentUser.requireId();
		return notifications.findById(id)
				.filter(n -> n.getUserId().equals(userId))
				.orElseThrow(() -> com.ahmadre.hinata.common.ApiException.notFound("notification"));
	}
}
