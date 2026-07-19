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
	 * Articles whose body contains the given (pre-built, injection-safe) regex —
	 * used server-side to resolve the issue⇄article backlink ({@code {{issue:KEY}}}
	 * tokens) instead of shipping the whole corpus to the client and scanning it
	 * there. The caller is responsible for building a safe literal token regex.
	 */
	@Query("{ 'content': { $regex: ?0 } }")
	List<Article> findByContentRegex(String regex);
}
