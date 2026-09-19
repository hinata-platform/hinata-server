package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.availability.TimeOffRepository;
import com.ahmadre.hinata.availability.TimeOffService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

	/** An absence as the approval flow needs to see it: when it runs, and nothing else. */
	public record Span(String id, LocalDate from, LocalDate to, boolean halfDay) {
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
	 * Cuts [absenceId] back to end on [lastDay] — § 9 BUrlG, where sickness eats into leave.
	 *
	 * <p>Shortened rather than deleted and rewritten, so the days that were always leave keep the
	 * document they were entered on.
	 */
	public void endOn(User actor, String absenceId, LocalDate lastDay) {
		absences.updateForRequest(actor, absenceId,
				new TimeOffService.Patch(null, null, null, lastDay, null, null));
	}

	/** What [absenceId] currently spans, or empty when it is gone. */
	public Optional<Span> spanOf(String absenceId) {
		if (absenceId == null || absenceId.isBlank()) {
			return Optional.empty();
		}
		return repository.findById(absenceId)
				.map(found -> new Span(found.getId(), found.getFrom(), found.getTo(), found.isHalfDay()));
	}

	/**
	 * Absences of [userId] that touch [from]–[to] — the § 9 BUrlG lookup.
	 *
	 * <p>Overlap is {@code to >= from AND from <= to}, which is the order the {@code user_to_from}
	 * index is built in, so this is a range read rather than a scan of everything the person was
	 * ever away for.
	 */
	public List<Span> touching(String userId, LocalDate from, LocalDate to) {
		return repository.findByUserIdAndToGreaterThanEqualAndFromLessThanEqual(userId, from, to).stream()
				.map(each -> new Span(each.getId(), each.getFrom(), each.getTo(), each.isHalfDay()))
				.toList();
	}
}
