package com.ahmadre.hinata.article;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface ArticleRepository extends MongoRepository<Article, String> {

	List<Article> findByProjectIdOrderBySortOrderAsc(String projectId);

	List<Article> findByProjectIdIsNullOrderBySortOrderAsc();

	List<Article> findAllByOrderBySortOrderAsc();

	List<Article> findByParentId(String parentId);

	List<Article> findBySpace(String space);

	/**
	 * Articles that link to the given readable issue id — the issue⇄article
	 * backlink. Answered from an index over references derived when the article
	 * was written, rather than by regex-scanning every body for a token.
	 */
	List<Article> findByReferencedIssueKeysContains(String issueKey);
}
