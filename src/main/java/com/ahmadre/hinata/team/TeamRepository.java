package com.ahmadre.hinata.team;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TeamRepository extends MongoRepository<Team, String> {

	boolean existsByKeyIgnoreCase(String key);

	/** Teams the user belongs to (derived query over the embedded members list). */
	List<Team> findByMembersUserId(String userId);

	/** Teams that own the given project — used when a project is deleted/archived. */
	List<Team> findByProjectIdsContains(String projectId);
}
