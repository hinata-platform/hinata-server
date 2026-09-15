package com.ahmadre.hinata.issue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drops the indexes on the issues that no query reads any more and every write still pays for, a drag
 * on the board most of all: the single-field indexes on {@code projectId} and {@code state} that older
 * servers created, since every query on a project goes through an index that starts with it and no
 * query asks for a state without its project, and the board indexes a pre-release build of the paged
 * board created, which the {@code board_by_*} indexes replace. Does nothing once they are gone.
 */
@Slf4j
@Component
@Order(41)
@RequiredArgsConstructor
public class IssueIndexCleanup implements ApplicationRunner {

	static final List<String> REDUNDANT = List.of("projectId", "state", "board_column", "board_sprint", "board_timeline");

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		IndexOperations indexes = mongo.indexOps(Issue.class);
		Set<String> present = indexes.getIndexInfo().stream().map(IndexInfo::getName).collect(Collectors.toSet());
		for (String name : REDUNDANT) {
			if (present.contains(name)) {
				indexes.dropIndex(name);
				log.info("IssueIndexCleanup: dropped the index {}, which no query needs", name);
			}
		}
	}
}
