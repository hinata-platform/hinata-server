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

	/** Projects whose PRIMARY repo matches — used to route inbound webhooks. */
	List<Project> findByGit_ProviderAndGit_OwnerIgnoreCaseAndGit_RepoIgnoreCase(
			String provider, String owner, String repo);

	/** Projects whose an ADDITIONAL repo matches — used to route inbound webhooks. */
	List<Project> findByExtraRepos_ProviderAndExtraRepos_OwnerIgnoreCaseAndExtraRepos_RepoIgnoreCase(
			String provider, String owner, String repo);
}
