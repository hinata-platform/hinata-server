package com.ahmadre.hinata.timetracking;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * That the moment for one reminder or one alert has been taken (HIN-92).
 *
 * <p>The whole claim is the {@code _id}: a key naming the subject, the kind and the period
 * or threshold, inserted once. A second instance, or the same one after a restart, inserts the
 * same key and is refused by the primary index, so nothing is delivered twice and no lock or
 * transaction is needed.
 *
 * <p>Deliberately without content. A reminder mark is written <em>before</em> the job looks at
 * the person's day, so it records that their reminder time came, never whether they had
 * recorded enough: a collection of "who fell short" would be exactly the evaluation of people
 * the module rules out (§ 87 Abs. 1 Nr. 6 BetrVG, R7). Marks expire after 90 days.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("time_reminder_marks")
public class TimeReminderMark {

	@Id
	private String id;

	/** When the mark was taken; the TTL index removes it 90 days later. */
	@Indexed(name = "at_ttl", expireAfter = "90d")
	private Instant at;
}
