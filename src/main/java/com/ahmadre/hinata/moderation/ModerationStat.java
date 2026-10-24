package com.ahmadre.hinata.moderation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One counter of one calendar year (UTC). No ids, no content, no people. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("moderation_stats")
@CompoundIndex(name = "year_metric_key", def = "{'year': 1, 'metric': 1, 'key': 1}", unique = true)
public class ModerationStat {

	@Id
	private String id;
	private int year;
	private StatMetric metric;
	private String key;
	private long count;
}
