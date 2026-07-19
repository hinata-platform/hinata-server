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

	@GetMapping
	public Page<Notification> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return notifications.findByUserIdOrderByCreatedAtDesc(
				currentUser.requireId(), PageRequest.of(page, Math.min(size, 100)));
	}

	@GetMapping("/unread-count")
	public Map<String, Long> unreadCount() {
		return Map.of("count", notifications.countByUserIdAndReadFalse(currentUser.requireId()));
	}

	@PostMapping("/{id}/read")
	public Notification markRead(@PathVariable String id) {
		Notification notification = owned(id);
		notification.setRead(true);
		return notifications.save(notification);
	}

	@PostMapping("/{id}/unread")
	public Notification markUnread(@PathVariable String id) {
		Notification notification = owned(id);
		notification.setRead(false);
		return notifications.save(notification);
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
