package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Classes written to break the shift-planning boundary, so {@code ModuleBoundaryTest} can show
 * that each rule fails when it should. Never Spring beans and never called: they exist to be
 * imported as bytecode.
 */
@SuppressWarnings("unused")
public final class ShiftBoundaryProbes {

	private ShiftBoundaryProbes() {
	}

	/** Reads time tracking, which shift planning may not. */
	public static class ReadsTimeTracking {
		private TimeTrackingSettings settings;
	}

	/** Notifies from inside its own transaction. */
	public static class NotifiesInsideTransaction {
		private NotificationService notifications;

		@Transactional
		void assign() {
			notifications.notifyShared(null, null, null);
		}
	}

	/** Audits from a method that runs in a transaction because its class says so. */
	@Transactional
	public static class AuditsInsideTransactionalClass {
		private AuditService audit;

		void release() {
			audit.event(null);
		}
	}

	/** Notifies from a lambda written inside the transactional method. */
	public static class NotifiesFromALambda {
		private NotificationService notifications;

		@Transactional
		void assignAll(List<String> people) {
			people.forEach(person -> notifications.notifyShared(null, null, person));
		}
	}

	/** The sanctioned shape: the transaction publishes, a listener delivers after the commit. */
	public static class DeliversAfterCommit {
		private NotificationService notifications;

		@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
		void onAssigned(Object event) {
			notifications.notifyShared(null, null, null);
		}
	}
}
