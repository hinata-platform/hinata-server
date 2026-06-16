package hn.asta.hivora.board;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SprintRepository extends MongoRepository<Sprint, String> {

	List<Sprint> findByBoardIdOrderByStartDateDesc(String boardId);

	/** Completed (archived) sprints, most recent first — the velocity history. */
	List<Sprint> findByBoardIdAndArchivedTrueOrderByEndDateDesc(String boardId);
}
