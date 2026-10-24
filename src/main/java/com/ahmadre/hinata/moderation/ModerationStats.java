package com.ahmadre.hinata.moderation;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.ahmadre.hinata.content.ReportCategory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts an event into the figures of its calendar year (HIN-170), one {@code $inc} upsert each,
 * by the keys the yearly figures read (HIN-178).
 */
@Component
@RequiredArgsConstructor
public class ModerationStats {

	/** Hours are counted one by one up to a month; longer ones in one bucket. */
	static final int HOURS_COUNTED = 720;

	/** The bucket of hours longer than {@link #HOURS_COUNTED}. */
	static final String OVERFLOW = "overflow";

	/** What a measure restricted, as the template names it. */
	public enum Restriction {
		/** Content taken out: removed, a field cleared, a page set back. */
		REMOVAL,
		/** An account suspended for a time. */
		SUSPENSION,
		/** An account suspended until somebody lifts it. */
		TERMINATION,
		/** Something dealt with outside Hinata. */
		OTHER
	}

	/** What an appeal is about, as the template names the complaints. */
	public enum Complaint {
		REMOVAL,
		ACCOUNT
	}

	private final MongoTemplate mongo;
	private final Clock clock;

	/** A new report by a member or by somebody without an account. */
	public void notice(ReportCategory category) {
		count(StatMetric.NOTICE, StatKey.of(category));
	}

	/**
	 * Case [c] decided as [decision] at [now]: an action on notices, or a measure of moderation's
	 * own. A decision that found nothing wrong is no measure and counts nothing.
	 */
	public void decided(ModerationCase c, Decision decision, Instant now) {
		if (!decision.acted() || decision.category() == null || decision.ground() == null) {
			return;
		}
		ModerationCase.Measures measures = c.measures();
		if (decision.outcome() == Decision.Outcome.ACTION && !measures.any()) {
			// An action whose suspension was refused restricted nothing.
			return;
		}
		ReportCategory category = decision.category();
		Decision.Ground ground = decision.ground();
		if (decision.basis() == Decision.Basis.NOTICE) {
			count(StatMetric.NOTICE_ACTION, StatKey.of(category, ground));
			count(StatMetric.NOTICE_HOURS, StatKey.of(category, hoursBucket(c.getFirstReportedAt(), now)));
			return;
		}
		count(StatMetric.OWN_MEASURE, StatKey.of(category, ground, decision.basis()));
		for (Restriction restriction : restrictionsOf(decision, measures)) {
			count(StatMetric.OWN_RESTRICTION, StatKey.of(category, ground, restriction));
		}
	}

	/** An appeal against [decision]. */
	public void appealFiled(Decision decision) {
		count(StatMetric.APPEAL_FILED, StatKey.of(complaintOf(decision)));
	}

	/** The appeal [appeal] against [decision] decided as [result] at [now]. */
	public void appealDecided(Decision decision, Appeal appeal, AppealService.AppealDecisionRequest.Result result,
			Instant now) {
		Complaint complaint = complaintOf(decision);
		count(StatMetric.APPEAL_RESULT, StatKey.of(complaint, result));
		count(StatMetric.APPEAL_HOURS, StatKey.of(complaint, hoursBucket(appeal == null ? null : appeal.filedAt(), now)));
	}

	/**
	 * What [decision] restricted, as its [measures] took effect; a removal and a suspension decided
	 * together are two.
	 */
	static List<Restriction> restrictionsOf(Decision decision, ModerationCase.Measures measures) {
		List<Restriction> restrictions = new ArrayList<>();
		if (measures.removal()) {
			restrictions.add(Restriction.REMOVAL);
		}
		if (decision.suspension() != null && measures.suspension()) {
			restrictions.add(decision.suspension() == Decision.SuspensionLength.INDEFINITE ? Restriction.TERMINATION
					: Restriction.SUSPENSION);
		}
		if (decision.outcome() == Decision.Outcome.HANDLED) {
			restrictions.add(Restriction.OTHER);
		}
		return restrictions;
	}

	/** A removal is what an appeal is about when there was one; otherwise the account. */
	static Complaint complaintOf(Decision decision) {
		return decision.removal() != null ? Complaint.REMOVAL : Complaint.ACCOUNT;
	}

	/** The bucket of the hours from [from] to [to]: {@code 0} to {@code 720}, then {@link #OVERFLOW}. */
	static String hoursBucket(Instant from, Instant to) {
		long hours = from == null ? 0 : Math.max(Duration.between(from, to).toHours(), 0);
		return hours > HOURS_COUNTED ? OVERFLOW : String.valueOf(hours);
	}

	/** One more of [metric] under [key], a {@link StatKey}; one name is a key of its own. */
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
