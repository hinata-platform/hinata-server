package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.AvailabilityAccess;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.availability.TimeOffRepository;
import com.ahmadre.hinata.people.PersonAbsences;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;

/**
 * Today's absence on a person's profile (HIN-165): a reader of availability for display, the
 * profile's one door to it.
 *
 * <p>Only while the extended module is on, whose routes the absences live under. The viewer's
 * sight is {@link AvailabilityAccess#of}: oneself and whoever keeps absences see everything, an
 * organisation admin and, by policy, a lead see type and span with a sick day as {@code OTHER},
 * everybody else nothing, not even that there is anything. One indexed range read
 * ({@code user_to_from}) after the sight allows it.
 */
@Component
@RequiredArgsConstructor
class TimeProfileAbsence implements PersonAbsences {

	private final TimeTrackingSettings settings;
	private final AvailabilityAccess access;
	private final TimeOffRepository timeOffs;

	@Override
	public Optional<Absence> today(User viewer, String userId, LocalDate today) {
		if (!settings.advancedEnabled()) return Optional.empty();
		AvailabilityAccess.Sight sight = access.of(viewer, userId);
		if (sight == AvailabilityAccess.Sight.NONE) return Optional.empty();
		return timeOffs.findByUserIdAndToGreaterThanEqualAndFromLessThanEqual(userId, today, today).stream()
				.min(Comparator.comparing(TimeOff::getFrom))
				.map(away -> new Absence(
						away.getType() == null ? null : AvailabilityAccess.typeFor(away.getType(), sight).name(),
						sight == AvailabilityAccess.Sight.FULL ? away.getTypeId() : null,
						away.getFrom(), away.getTo(), away.getHalfDay()));
	}
}
