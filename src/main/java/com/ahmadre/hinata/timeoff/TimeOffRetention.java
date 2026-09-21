package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The storage limitation of absence data, applied once a night (HIN-119, R5).
 *
 * <p>Three rules, each a policy with a default, and one thing it never touches:
 *
 * <ul>
 * <li><b>Sick details</b> after {@code sickDetailPurgeMonths} (12): a sick absence becomes "away
 * (other)" and loses its note, and a sick report its notes. The day, its effect on capacity and on
 * any balance stay; the health fact goes (Art. 5 Abs. 1 lit. e, Art. 9 DSGVO).</li>
 * <li><b>Closed requests</b> after {@code requestPurgeMonths} (36): what was refused, withdrawn or
 * cancelled decided nothing that still binds anybody. Approved requests are the evidence of leave
 * granted and stay with the absence they created.</li>
 * <li><b>The journal</b> only when an operator sets {@code ledgerPurgeYears}, and then never fewer
 * than three years (§§ 195, 199 BGB, § 28f Abs. 1 SGB IV).</li>
 * <li><b>Never</b>: the notices that leave was about to lapse. They are what a lapse rests on, and
 * the limitation period of the claim starts only with them.</li>
 * </ul>
 *
 * <p>Checks the module switch itself, like every job here.
 */
@Component
@RequiredArgsConstructor
class TimeOffRetention {

	/** The request states that decided nothing still binding. */
	static final List<TimeOffRequest.Status> CLOSED = List.of(TimeOffRequest.Status.REJECTED,
			TimeOffRequest.Status.WITHDRAWN, TimeOffRequest.Status.CANCELLED);

	private final TimeOffSettings settings;
	private final TimeTrackingSettings policy;
	private final TimeOffTypeRepository types;
	private final TimeOffAbsences absences;
	private final MongoTemplate mongo;
	private final AuditService audit;
	private final Clock clock;

	/** Tonight's sweep, or empty when the module is off or another instance has the night. */
	Optional<TimeOffRetentionRun> run() {
		if (!settings.enabled()) {
			return Optional.empty();
		}
		LocalDate today = LocalDate.now(clock);
		TimeOffRetentionRun run;
		try {
			run = mongo.insert(TimeOffRetentionRun.builder().id(today.toString()).startedAt(clock.instant()).build());
		}
		catch (DuplicateKeyException claimed) {
			return Optional.empty();
		}
		TimeTrackingSettings.TimeOffRetention rule = policy.timeOffRetention();
		try {
			if (rule.sickDetailPurgeMonths() > 0) {
				run.setSickCoarsened(coarsenSick(today.minusMonths(rule.sickDetailPurgeMonths())));
			}
			if (rule.requestPurgeMonths() > 0) {
				run.setRequestsRemoved(mongo.remove(Query.query(Criteria.where("status").in(CLOSED)
						.and("to").lt(today.minusMonths(rule.requestPurgeMonths()))), TimeOffRequest.class)
						.getDeletedCount());
			}
			if (rule.ledgerPurgeYears() > 0) {
				run.setLedgerRemoved(mongo.remove(Query.query(Criteria.where("year")
						.lt(today.getYear() - rule.ledgerPurgeYears())), TimeOffLedgerEntry.class).getDeletedCount());
			}
		}
		finally {
			run.setFinishedAt(clock.instant());
			mongo.save(run);
			audit.event(AuditAction.TIME_OFF_RETENTION_RUN)
					.meta("sickCoarsened", String.valueOf(run.getSickCoarsened()))
					.meta("requestsRemoved", String.valueOf(run.getRequestsRemoved()))
					.meta("ledgerRemoved", String.valueOf(run.getLedgerRemoved()))
					.log();
		}
		return Optional.of(run);
	}

	/**
	 * Coarsens every sick absence and sick report that ended before [before]: the absences to
	 * "away (other)", and on the requests of a sick type the type and every note. Returns how many
	 * documents changed.
	 */
	private long coarsenSick(LocalDate before) {
		String other = types.findBySystemKey(TimeOffType.SYSTEM_OTHER).map(TimeOffType::getId).orElse(null);
		List<String> sickTypes = types.findAll().stream()
				.filter(type -> type.getKind() == TimeOffType.Kind.SICK)
				.map(TimeOffType::getId).toList();
		long changed = absences.coarsenSickBefore(before, other);
		if (!sickTypes.isEmpty()) {
			Update update = new Update().unset("note").unset("decisionNote").unset("history.$[].note");
			if (other != null) {
				update.set("typeId", other);
			}
			changed += mongo.updateMulti(Query.query(Criteria.where("typeId").in(sickTypes).and("to").lt(before)),
					update, TimeOffRequest.class).getModifiedCount();
		}
		return changed;
	}
}
