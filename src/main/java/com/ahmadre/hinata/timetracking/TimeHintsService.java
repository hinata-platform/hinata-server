package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * The Working Hours Act hints and the late-entry hint for one person's own entries.
 *
 * <p>The rules are {@link WorkingTimeHints}; this reads what they need and nothing
 * more. Why nobody may ask it about somebody else is written on
 * {@link TimeHintsController}.
 */
@Service
@RequiredArgsConstructor
class TimeHintsService {

	/**
	 * Most entries one window reads. Thirty-two days of a person's own entries is a
	 * few dozen in practice; this bounds a client that files hundreds a day.
	 */
	static final int ENTRY_CAP = 2_000;

	private final TimeTrackingSettings policy;
	private final TimeTrackingService entries;
	private final MongoTemplate mongo;

	/**
	 * The hints on {@code user}'s own entries from {@code from} to {@code to}.
	 *
	 * <p>404 with the module's "disabled" key while neither hint is switched on, the
	 * way every route of a policy that is off answers.
	 */
	List<WorkingTimeHints.Hint> hintsFor(LocalDate from, LocalDate to, User user) {
		WorkingTimeHints.Rules rules = new WorkingTimeHints.Rules(policy.arbzgHintsEnabled(),
				policy.lateEntryHintDays());
		if (!rules.workingTimeAct() && rules.lateEntryHintDays() == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, AdvancedTimeTrackingGate.DISABLED_KEY);
		}
		entries.assertWindow(from, to);
		// One day before the window as well: whether the rest before its first day
		// was long enough depends on when the day before it ended.
		Query query = Query.query(Criteria.where("userId").is(user.getId())
				.and("date").gte(from.minusDays(1)).lte(to)).limit(ENTRY_CAP);
		query.fields().include("date", "durationMinutes", "startedAt", "endedAt", "createdAt");
		List<WorkingTimeHints.Entry> own = mongo.query(WorkItem.class).as(Document.class)
				.matching(query).all().stream()
				.map(document -> new WorkingTimeHints.Entry(WorkItemDocuments.id(document),
						WorkItemDocuments.day(document, "date"), WorkItemDocuments.minutes(document),
						WorkItemDocuments.instant(document, "startedAt"),
						WorkItemDocuments.instant(document, "endedAt"),
						WorkItemDocuments.instant(document, "createdAt")))
				.toList();
		return WorkingTimeHints.of(own, from, to, entries.zoneOf(user), rules);
	}
}
