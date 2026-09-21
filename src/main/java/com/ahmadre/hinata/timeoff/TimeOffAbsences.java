package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.CapacityService;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.availability.TimeOffRepository;
import com.ahmadre.hinata.availability.TimeOffService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The absences an approved request leaves behind: written, shortened, taken back.
 *
 * <p>This is the whole contact surface between the approval flow and the calendar it writes into,
 * in one class with one name — the shape {@code timetracking.ModuleBoundaryTest} asks for, since
 * every class in this module that touches {@code availability} has to be named in it. A service
 * that reached into the calendar from five places would be five names to keep in that list and
 * five places to check when the question "who can learn that somebody is sick?" comes up again.
 *
 * <p>Nothing of {@code availability} leaves through here. What comes back is ids and dates, so the
 * request service never holds a {@link TimeOff} and cannot accidentally pass one somewhere it does
 * not belong.
 */
@Component
@RequiredArgsConstructor
public class TimeOffAbsences {

	private final TimeOffService absences;
	private final TimeOffRepository repository;
	private final CapacityService capacity;
	private final MongoTemplate mongo;

	/**
	 * How much of a window one person was planned to work and how much of it they were away:
	 * the two numbers an absence rate is a share of (HIN-119). Minutes, as capacity counts them.
	 */
	public record AwayShare(int scheduledMinutes, int holidayMinutes, int absenceMinutes) {

		/** Away as a share of the working time the window planned, in thousandths; null for none planned. */
		public Integer permille() {
			int working = scheduledMinutes - holidayMinutes;
			return working <= 0 ? null : (int) Math.round(absenceMinutes * 1000.0 / working);
		}
	}

	/**
	 * Writes the absence an approved request earned, and returns its id.
	 *
	 * <p>Goes in through the door that does not ask whether this was the right road: the decision
	 * has already been made, by somebody the request named, and a lead deciding for their team is
	 * not somebody who keeps absences for the organisation.
	 */
	public String enter(User decider, User person, String requestId, String typeId, LocalDate from,
			LocalDate to, boolean halfDay, String note) {
		TimeOff written = absences.enter(decider, person,
				new TimeOffService.Draft(person.getId(), null, typeId, from, to, halfDay ? Boolean.TRUE : null, note),
				requestId);
		return written.getId();
	}

	/**
	 * Removes the absence a cancelled request had created, if it is still there. Through the door
	 * for requests: the direct one refuses an absence a request produced, which this one is.
	 */
	public void remove(User actor, String absenceId) {
		if (absenceId == null || absenceId.isBlank()) {
			return;
		}
		repository.findById(absenceId).ifPresent(found -> absences.deleteForRequest(actor, absenceId));
	}

	/**
	 * Each of [userIds]' planned time and time away over a window, as capacity reads it — every
	 * kind of absence, sickness included, which is why only a keeper ever sees the share.
	 */
	public Map<String, AwayShare> awayShares(Collection<String> userIds, LocalDate from, LocalDate to) {
		Map<String, AwayShare> shares = new HashMap<>();
		capacity.totals(userIds, from, to).forEach((userId, total) -> shares.put(userId,
				new AwayShare(total.scheduledMinutes(), total.holidayMinutes(), total.absenceMinutes())));
		return shares;
	}

	/**
	 * Calendar days of [userId]'s sick absences inside [from]–[to]. For the proposal after a long
	 * illness, which asks a keeper to look, and never decides anything with the number itself.
	 */
	public int sickDays(String userId, LocalDate from, LocalDate to) {
		int days = 0;
		for (TimeOff absence : repository.findByUserIdAndToGreaterThanEqualAndFromLessThanEqual(userId, from, to)) {
			if (absence.getType() != TimeOff.Type.SICK) {
				continue;
			}
			LocalDate start = absence.getFrom().isBefore(from) ? from : absence.getFrom();
			LocalDate end = absence.getTo().isAfter(to) ? to : absence.getTo();
			if (!end.isBefore(start)) {
				days += (int) ChronoUnit.DAYS.between(start, end) + 1;
			}
		}
		return days;
	}

	/**
	 * Turns every sick absence that ended before [before] into "away (other)" and drops its note
	 * (HIN-119, Art. 5 Abs. 1 lit. e and Art. 9 DSGVO). The days stay, and so does their effect on
	 * capacity and on any balance: what goes is the health fact. Returns how many were coarsened.
	 */
	public long coarsenSickBefore(LocalDate before, String otherTypeId) {
		Update update = new Update().set("type", TimeOff.Type.OTHER).unset("note");
		if (otherTypeId != null) {
			update.set("typeId", otherTypeId);
		}
		return mongo.updateMulti(Query.query(Criteria.where("type").is(TimeOff.Type.SICK).and("to").lt(before)),
				update, TimeOff.class).getModifiedCount();
	}

	/**
	 * Cuts [absenceId] back to end on [lastDay] — § 9 BUrlG, where sickness eats into leave.
	 *
	 * <p>Shortened rather than deleted and rewritten, so the days that were always leave keep the
	 * document they were entered on.
	 */
	public void endOn(User actor, String absenceId, LocalDate lastDay) {
		absences.updateForRequest(actor, absenceId,
				new TimeOffService.Patch(null, null, null, lastDay, null, null));
	}
}
