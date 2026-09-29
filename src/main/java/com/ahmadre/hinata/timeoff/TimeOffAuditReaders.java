package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AbsenceRecordReaders;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The audit log's answer to "who reads absence records": exactly who keeps
 * absences ({@link TimeOffAccess#isKeeper}). An organisation admin outside a
 * named circle does not see who reported sick in the log either.
 */
@Component
@RequiredArgsConstructor
class TimeOffAuditReaders implements AbsenceRecordReaders {

	private final TimeOffAccess access;

	@Override
	public boolean reads(User reader) {
		return reader != null && access.isKeeper(reader);
	}
}
