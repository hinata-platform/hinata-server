package com.ahmadre.hinata.team;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TeamActivityRepository extends MongoRepository<TeamActivity, String> {

	Page<TeamActivity> findByTeamIdOrderByCreatedAtDesc(String teamId, Pageable pageable);

	void deleteByTeamId(String teamId);
}
