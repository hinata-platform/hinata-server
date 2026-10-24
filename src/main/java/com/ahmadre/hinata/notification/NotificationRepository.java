package com.ahmadre.hinata.notification;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRepository extends MongoRepository<Notification, String> {

	/**
	 * The bell, newest first a page at a time. Off the {@code user_created} index, with the id as
	 * the tiebreaker, so a page is read in order instead of sorting a person's whole history first,
	 * and two notices of the same instant never trade places between pages.
	 */
	Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(String userId, Pageable pageable);

	long countByUserIdAndReadFalse(String userId);

	/** Whether a notice of [type] went out to [userId] by mail or push since [since]; off {@code user_created}. */
	boolean existsByUserIdAndTypeAndSentOutTrueAndCreatedAtAfter(String userId, Notification.Type type,
			java.time.Instant since);

	long deleteByUserIdAndType(String userId, Notification.Type type);

	List<Notification> findByUserIdInAndLink(java.util.Collection<String> userIds, String link);
}
