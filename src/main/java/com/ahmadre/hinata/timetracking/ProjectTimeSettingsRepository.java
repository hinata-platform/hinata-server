package com.ahmadre.hinata.timetracking;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProjectTimeSettingsRepository
		extends MongoRepository<ProjectTimeSettings, String> {

	Optional<ProjectTimeSettings> findByProjectId(String projectId);
}
