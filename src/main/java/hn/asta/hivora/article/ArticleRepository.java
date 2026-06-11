package hn.asta.hivora.article;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ArticleRepository extends MongoRepository<Article, String> {

	List<Article> findByProjectIdOrderBySortOrderAsc(String projectId);

	List<Article> findByProjectIdIsNullOrderBySortOrderAsc();

	List<Article> findByParentId(String parentId);
}
