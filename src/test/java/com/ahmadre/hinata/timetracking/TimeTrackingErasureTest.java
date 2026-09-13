package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an account deletion leaves behind in the module when a write fails on the way.
 * The integration test covers the happy path against a database; these are the two
 * failures a database cannot be asked to produce on cue.
 */
class TimeTrackingErasureTest {

	private final RunningTimerRepository timers = mock(RunningTimerRepository.class);
	private final TimesheetApprovalRepository approvals = mock(TimesheetApprovalRepository.class);
	private final DepartedTimeUserRepository departed = mock(DepartedTimeUserRepository.class);
	private final TimeCorrectionRequestRepository corrections =
			mock(TimeCorrectionRequestRepository.class);
	private final TimeBackfillGrantRepository grants = mock(TimeBackfillGrantRepository.class);

	private final TimeTrackingErasure erasure = new TimeTrackingErasure(timers, approvals, departed,
			corrections, grants, Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC));

	@Test
	void aPseudonymRecordThatFailsOnceIsWrittenOnTheNextTry() {
		when(departed.save(any())).thenThrow(new DataAccessResourceFailureException("primary stepped down"))
				.thenAnswer(invocation -> invocation.getArgument(0));

		erasure.onUserDeleted(new UserService.UserDeletedEvent("u-gone"));

		// Without it the retention sweep would never learn that this person is gone.
		verify(departed, times(2)).save(any(DepartedTimeUser.class));
	}

	@Test
	void oneStepFailingDoesNotStopTheOthers() {
		when(timers.deleteByUserId("u-gone")).thenThrow(new DataAccessResourceFailureException("down"));

		erasure.onUserDeleted(new UserService.UserDeletedEvent("u-gone"));

		verify(approvals).deleteByUserIdAndStatusNot("u-gone", TimesheetApproval.Status.APPROVED);
		verify(corrections).deleteByUserId("u-gone");
		verify(grants).deleteByUserId("u-gone");
		verify(departed).save(any(DepartedTimeUser.class));
	}
}
