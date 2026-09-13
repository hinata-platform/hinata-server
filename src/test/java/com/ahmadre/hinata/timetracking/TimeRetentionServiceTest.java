package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one promise of the retention sweep a database cannot be asked to break on cue:
 * a run that dies half way still leaves its record behind. The record that records
 * were deleted has to outlive them, and a failure is when somebody reads it.
 */
class TimeRetentionServiceTest {

	@Test
	void aRunThatFailsIsStillRecordedAsIncompleteWithTheReason() {
		TimeTrackingSettings policy = mock(TimeTrackingSettings.class);
		when(policy.advancedEnabled()).thenReturn(true);
		when(policy.retention()).thenReturn(new TimeTrackingSettings.Retention(0, 24));
		MongoTemplate mongo = mock(MongoTemplate.class);
		when(mongo.query(TimesheetApproval.class))
				.thenThrow(new DataAccessResourceFailureException("connection lost"));
		AuditService audit = mock(AuditService.class);
		AuditService.Entry record = mock(AuditService.Entry.class, Answers.RETURNS_SELF);
		when(audit.event(AuditAction.TIME_RETENTION_RUN)).thenReturn(record);
		TimeRetentionService service = new TimeRetentionService(policy,
				mock(DepartedTimeUserRepository.class), audit, mongo,
				Clock.fixed(Instant.parse("2026-09-10T03:45:00Z"), ZoneOffset.UTC));

		assertThatThrownBy(service::run).isInstanceOf(DataAccessResourceFailureException.class);

		verify(mongo).insert(any(TimeRetentionRun.class));
		verify(record).meta("complete", "false");
		verify(record).meta("failure", "DataAccessResourceFailureException");
		verify(record).log();
	}
}
