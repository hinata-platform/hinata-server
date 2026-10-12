package com.ahmadre.hinata.notification;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends MongoRepository<DeviceToken, String> {

	List<DeviceToken> findByUserId(String userId);

	/**
	 * The tokens of [userId] that are not of [kind]. {@code $ne} also matches a
	 * document without the field, which is how every token written before kinds
	 * existed is found as a push token.
	 */
	List<DeviceToken> findByUserIdAndKindNot(String userId, DeviceToken.Kind kind);

	List<DeviceToken> findByUserIdAndKindOrderByCreatedAtAsc(String userId, DeviceToken.Kind kind);

	Optional<DeviceToken> findByToken(String token);

	void deleteByToken(String token);

	long deleteByUserId(String userId);
}
