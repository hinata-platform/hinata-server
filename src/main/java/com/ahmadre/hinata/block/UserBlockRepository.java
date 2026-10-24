package com.ahmadre.hinata.block;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

interface UserBlockRepository extends MongoRepository<UserBlock, String> {

	boolean existsByBlockerIdAndBlockedId(String blockerId, String blockedId);

	long countByBlockerId(String blockerId);

	Page<UserBlock> findByBlockerIdOrderByCreatedAtDescIdDesc(String blockerId, Pageable page);

	long deleteByBlockerIdAndBlockedId(String blockerId, String blockedId);

	long deleteByBlockerIdOrBlockedId(String blockerId, String blockedId);
}
