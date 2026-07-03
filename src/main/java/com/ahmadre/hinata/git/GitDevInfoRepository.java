package com.ahmadre.hinata.git;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface GitDevInfoRepository extends MongoRepository<GitDevInfo, String> {

	Optional<GitDevInfo> findByIssueKeyIgnoreCase(String issueKey);

	List<GitDevInfo> findByProjectId(String projectId);

	long deleteByProjectId(String projectId);
}
