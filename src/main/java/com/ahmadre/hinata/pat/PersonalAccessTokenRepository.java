package com.ahmadre.hinata.pat;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PersonalAccessTokenRepository extends MongoRepository<PersonalAccessToken, String> {

	Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

	List<PersonalAccessToken> findByUserIdOrderByCreatedAtDesc(String userId);

	long countByUserIdAndRevokedFalse(String userId);
}
