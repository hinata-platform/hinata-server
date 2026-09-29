package com.ahmadre.hinata.notification;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRepository extends MongoRepository<Notification, String> {

	Page<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	long countByUserIdAndReadFalse(String userId);

	long deleteByUserIdAndType(String userId, Notification.Type type);

	List<Notification> findByUserIdInAndLink(java.util.Collection<String> userIds, String link);
}
