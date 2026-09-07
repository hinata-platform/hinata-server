package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What the module deletes when an account is deleted, and what it keeps.
 *
 * <p>Deleted: the running timer. It is live personal state about what somebody
 * is doing right now, it belongs to nobody once the account is gone, and it
 * would otherwise sit in {@code running_timers} forever holding a name for an id
 * that no longer resolves — swept hourly by a job that can never file it.
 *
 * <p>Kept: the entries. Hours worked on a project are the project's record, and
 * {@code work_items.userId} stays as a pseudonym exactly as a comment's author
 * id does ({@code UserService.delete} states the convention). Art. 17 DSGVO is
 * satisfied by the account being gone; the retention policy of stage 8 is what
 * empties the descriptions afterwards, on a schedule the operator sets.
 *
 * <p>It listens rather than being called, so that {@code user} carries no
 * knowledge of this module — the same direction as {@code FeatureFlags.Module}
 * and {@code SettingsPrefill}. That inversion is what lets the whole package be
 * deleted without editing anything outside it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeTrackingErasure {

	private final RunningTimerRepository timers;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		try {
			long removed = timers.deleteByUserId(event.userId());
			if (removed > 0) {
				log.info("[time] removed running timer of deleted user {}", event.userId());
			}
		}
		catch (RuntimeException ex) {
			// The account is already gone. Failing here would turn a completed
			// erasure into a failed request and tell the user their deletion did
			// not happen, which is both alarming and untrue.
			log.warn("[time] could not remove running timer of deleted user {}: {}",
					event.userId(), ex.toString());
		}
	}
}
