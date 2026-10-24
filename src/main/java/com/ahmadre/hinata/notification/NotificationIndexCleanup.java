package com.ahmadre.hinata.notification;

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
 * Drops the single-field index on a notification's {@code userId} that older servers created: the
 * bell's {@code user_created} index starts with the same field and serves every query it served,
 * while every notification written still paid for both. Does nothing once it is gone.
 */
@Slf4j
@Component
@Order(41)
@RequiredArgsConstructor
public class NotificationIndexCleanup implements ApplicationRunner {

	static final List<String> REDUNDANT = List.of("userId");

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		IndexOperations indexes = mongo.indexOps(Notification.class);
		Set<String> present = indexes.getIndexInfo().stream().map(IndexInfo::getName).collect(Collectors.toSet());
		for (String name : REDUNDANT) {
			if (present.contains(name)) {
				indexes.dropIndex(name);
				log.info("NotificationIndexCleanup: dropped the index {}, which no query needs", name);
			}
		}
	}
}
