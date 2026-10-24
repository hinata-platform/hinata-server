package com.ahmadre.hinata.moderation;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;

/** Counts an event into the figures of its calendar year (HIN-170), one {@code $inc} upsert each. */
@Component
@RequiredArgsConstructor
public class ModerationStats {

	private final MongoTemplate mongo;
	private final Clock clock;

	public void count(StatMetric metric, String key) {
		int year = clock.instant().atZone(ZoneOffset.UTC).getYear();
		Query query = Query.query(Criteria.where("year").is(year).and("metric").is(metric).and("key").is(key));
		Update update = new Update().inc("count", 1L);
		try {
			mongo.upsert(query, update, ModerationStat.class);
		}
		catch (DuplicateKeyException raced) {
			// Two first counts of the same key at once: the other made the row, this one adds to it.
			mongo.updateFirst(query, update, ModerationStat.class);
		}
	}
}
