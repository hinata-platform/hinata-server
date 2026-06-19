package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationRepository notifications;
	private final CurrentUser currentUser;

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
		String userId = currentUser.requireId();
		Notification notification = notifications.findById(id)
				.filter(n -> n.getUserId().equals(userId))
				.orElseThrow(() -> com.ahmadre.hinata.common.ApiException.notFound("notification"));
		notification.setRead(true);
		return notifications.save(notification);
	}
}
