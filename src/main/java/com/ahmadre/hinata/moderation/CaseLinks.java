package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.user.SuspensionCases;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** The case behind a suspension, for whoever may open it ({@link CaseAccess.Purpose#SEE}). */
@Component
@RequiredArgsConstructor
class CaseLinks implements SuspensionCases {

	private final MongoTemplate mongo;
	private final CaseAccess access;

	@Override
	public boolean mayOpen(String caseId, User viewer) {
		ModerationCase c = caseId == null ? null : mongo.findById(caseId, ModerationCase.class);
		return c != null && access.eligible(viewer, c, CaseAccess.Purpose.SEE);
	}
}
