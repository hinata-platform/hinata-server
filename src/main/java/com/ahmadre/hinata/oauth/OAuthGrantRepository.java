package com.ahmadre.hinata.oauth;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OAuthGrantRepository extends MongoRepository<OAuthGrant, String> {

	Optional<OAuthGrant> findByRefreshTokenHash(String refreshTokenHash);
}
