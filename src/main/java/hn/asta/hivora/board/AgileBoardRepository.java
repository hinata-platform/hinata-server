package hn.asta.hivora.board;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AgileBoardRepository extends MongoRepository<AgileBoard, String> {

	List<AgileBoard> findByProjectIdsContains(String projectId);
}
