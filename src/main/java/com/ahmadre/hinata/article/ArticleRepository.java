package com.ahmadre.hinata.article;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ArticleRepository extends MongoRepository<Article, String> {

	List<Article> findByProjectIdOrderBySortOrderAsc(String projectId);

	List<Article> findByProjectIdIsNullOrderBySortOrderAsc();

	List<Article> findAllByOrderBySortOrderAsc();

	List<Article> findByParentId(String parentId);
}
