package com.ahmadre.hinata.audit;

import com.ahmadre.hinata.user.User;

/**
 * Who may read the audit records about absences (requests, sick reports, balances):
 * the people who keep absences, which is a narrower circle than the organisation
 * admins once an operator names one. Answered by the absence module; asked here so
 * the audit package does not depend on it.
 */
public interface AbsenceRecordReaders {

	boolean reads(User reader);
}
