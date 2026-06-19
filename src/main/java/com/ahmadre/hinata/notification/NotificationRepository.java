package com.ahmadre.hinata.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRepository extends MongoRepository<Notification, String> {

	Page<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	long countByUserIdAndReadFalse(String userId);
}
