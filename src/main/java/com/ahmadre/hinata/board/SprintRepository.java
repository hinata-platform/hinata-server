package com.ahmadre.hinata.board;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SprintRepository extends MongoRepository<Sprint, String> {

	List<Sprint> findByBoardIdOrderByStartDateDesc(String boardId);

	/** The five most recently completed (archived) sprints: the velocity history. */
	List<Sprint> findTop5ByBoardIdAndArchivedTrueOrderByEndDateDesc(String boardId);
}
