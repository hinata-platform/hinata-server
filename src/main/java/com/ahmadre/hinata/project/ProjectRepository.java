package com.ahmadre.hinata.project;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends MongoRepository<Project, String> {

	Optional<Project> findByKeyIgnoreCase(String key);

	boolean existsByKeyIgnoreCase(String key);

	List<Project> findByArchivedFalse();

	List<Project> findByArchivedTrue();

	List<Project> findByMemberIdsContainsAndArchivedFalse(String userId);

	List<Project> findByMemberIdsContainsAndArchivedTrue(String userId);
}
