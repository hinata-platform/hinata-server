package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The one class of the module that writes audit records, and only after the commit.
 *
 * <p>{@code ModuleBoundaryTest} refuses a call to the audit or notification service from any
 * other class of the module: a write that delivered from inside its transaction would have told
 * somebody about a change that was then rolled back, or told them twice after a retry.
 */
@Component
@RequiredArgsConstructor
public class ShiftDelivery {

	private final AuditService audit;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAudited(ShiftDomainEvents.Audited event) {
		AuditService.Entry entry = audit.event(event.action())
				.actor(event.actorId(), event.actorLabel())
				.target(event.targetId(), null);
		event.meta().forEach(entry::meta);
		entry.log();
	}
}
