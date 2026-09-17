package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What an account deletion removes here: the joining and leaving dates, and nothing else.
 *
 * <p>The dates exist for one purpose — waiting period, twelfths and settlement (§§ 4, 5, 7 Abs. 4
 * BUrlG) — and once there is nobody left to compute an entitlement for, that purpose is gone
 * (Art. 5 Abs. 1 lit. e DSGVO).
 *
 * <p>Grants and balance movements stay, following the convention {@code UserService.delete} already
 * sets for work items: the person's id remains as a pseudonym and the record remains readable. It
 * is the evidence of leave granted and taken, which is what §§ 195/199 BGB claims are argued over
 * and what § 28f Abs. 1 SGB IV expects an employer to be able to produce. When it goes is the
 * retention policy's decision in A4, not this listener's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeOffErasure {

	private final TimeOffEmploymentRepository employment;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		try {
			long removed = employment.deleteByUserId(event.userId());
			if (removed > 0) {
				log.info("[timeoff] employment dates removed for deleted user {}", event.userId());
			}
		}
		catch (RuntimeException ex) {
			// The account is already gone; a failure here must not turn a completed erasure into a
			// half-finished one that the person is told failed.
			log.warn("[timeoff] could not remove employment dates for deleted user {}: {}",
					event.userId(), ex.toString());
		}
	}
}
