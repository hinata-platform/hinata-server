package com.ahmadre.hinata.oauth;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface OAuthAuthorizationRequestRepository
		extends MongoRepository<OAuthAuthorizationRequest, String> {
}
