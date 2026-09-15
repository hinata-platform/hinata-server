package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * What an account deletion removes here: the person's working-time patterns and absences. Both
 * are purely personal and part of no project's record (R4).
 *
 * <p>Holiday calendars stay; they belong to the instance, not to whoever created them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvailabilityErasure {

	private final WorkingScheduleRepository schedules;
	private final TimeOffRepository timeOff;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		String userId = event.userId();
		step("working-time pattern(s)", userId, () -> schedules.deleteByUserId(userId));
		step("absence(s)", userId, () -> timeOff.deleteByUserId(userId));
	}

	private void step(String what, String userId, LongSupplier action) {
		try {
			long removed = action.getAsLong();
			if (removed > 0) {
				log.info("[availability] {}: {} for deleted user {}", what, removed, userId);
			}
		}
		catch (RuntimeException ex) {
			// The account is already gone; a failure here must not turn a completed erasure into a
			// failed request.
			log.warn("[availability] could not remove {} of deleted user {}: {}", what, userId, ex.toString());
		}
	}
}
