package com.ahmadre.hinata.notification;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends MongoRepository<DeviceToken, String> {

	List<DeviceToken> findByUserId(String userId);

	Optional<DeviceToken> findByToken(String token);

	void deleteByToken(String token);

	void deleteByUserId(String userId);
}
