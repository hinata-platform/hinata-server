package com.ahmadre.hinata.git;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for in-flight OAuth flows (keyed by the {@code state} value). */
public interface GitOAuthSessionRepository extends MongoRepository<GitOAuthSession, String> {
}
