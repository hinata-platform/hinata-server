package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * The one rule about the time-tracking settings that a field annotation cannot
 * state: <b>the lock date is never in the future.</b>
 *
 * <p>Until this existed, {@code lockBefore} accepted any date and the app's picker
 * offered ten years of them. A date set a month ahead freezes today and tomorrow —
 * and because {@link TimeLocks} is the gate every write to an entry passes,
 * it does not disable one feature but all of them: the composer, the timer, the
 * CSV import of HIN-93, the calendar takeover of HIN-94, shared entries in HIN-95
 * and the MCP write tools of HIN-97. The instance would then refuse to record
 * working time that is being performed at that moment.
 *
 * <p>That is the one place the implementation could contradict the law it is built
 * around. The obligation is to <em>keep</em> a system with which actual working
 * time can be recorded (EuGH 14.05.2019 – C-55/18 <i>CCOO</i>; BAG 13.09.2022 –
 * 1 ABR 22/21; § 16 Abs. 2 ArbZG); software that blocks the recording defeats it
 * and puts quiet pressure on people not to report overtime at all. So: the past
 * may be closed, the present and the future never.
 *
 * <p>A lock date arriving from the <em>environment</em> is not refused — nobody is
 * there to be told, and a server that will not start is worse than one that
 * freezes a day too few. {@link TimeTrackingSettings#lockBefore()} is where that
 * clamp belongs, and it is there.
 */
@Component
@RequiredArgsConstructor
public class TimeTrackingSettingsGuard implements SettingsGuard {

	private final Clock clock;

	@Override
	public void check(ServerSettings before, ServerSettings after) {
		ServerSettings.TimeTracking block = after == null ? null : after.getTimeTracking();
		if (block == null) {
			return;
		}
		LocalDate today = LocalDate.now(clock);
		// Today itself is allowed: isLocked() is strictly "before", so a lock date
		// of today freezes yesterday and leaves today open — which is exactly the
		// line this rule is drawing.
		if (block.getLockBefore() != null && block.getLockBefore().isAfter(today)) {
			throw ApiException.badRequest("error.time.lockDateInFuture");
		}
		assertExceptionsCoherent(block.getLockExceptions());
	}

	/**
	 * Exceptions arrive through their own route, which mints the author and the
	 * timestamp — but the settings PUT is a whole-document write, so a client could
	 * post the block with hand-written ones. The shape is checked here rather than
	 * trusted: an exception with no reason, or one spanning a year, is exactly the
	 * thing the route refuses.
	 */
	private static void assertExceptionsCoherent(
			List<ServerSettings.TimeTracking.LockException> exceptions) {
		if (exceptions == null) {
			return;
		}
		if (exceptions.size() > TimePolicy.LOCK_EXCEPTIONS_MAX) {
			throw ApiException.badRequest("error.time.lockExceptionsTooMany");
		}
		for (ServerSettings.TimeTracking.LockException exception : exceptions) {
			if (exception.getFrom() == null || exception.getTo() == null
					|| exception.getTo().isBefore(exception.getFrom())) {
				throw ApiException.badRequest("error.time.lockExceptionInvalid");
			}
			if (exception.getNote() == null || exception.getNote().isBlank()) {
				throw ApiException.badRequest("error.time.lockExceptionNoteRequired");
			}
			if (exception.getFrom().plusDays(TimePolicy.PERIOD_MAX_DAYS - 1L)
					.isBefore(exception.getTo())) {
				throw ApiException.badRequest("error.time.periodTooLong");
			}
		}
	}
}
