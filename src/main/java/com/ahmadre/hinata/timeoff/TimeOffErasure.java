package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What an account deletion removes here: the joining and leaving dates, and the requests nobody
 * ever decided.
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
 *
 * <p><b>A request splits along the same line.</b> One that was decided is part of that evidence and
 * stays, pseudonymous like the booking it caused. One that was still waiting, or that the person
 * took back, decided nothing and evidences nothing — and it would sit in somebody's inbox forever,
 * asking them to rule on an account that no longer exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeOffErasure {

	private final TimeOffEmploymentRepository employment;
	private final TimeOffRequestRepository requests;
	private final TimeOffProposalRepository proposals;

	/** The states in which a request never decided anything, so nothing is lost by dropping it. */
	private static final List<TimeOffRequest.Status> UNDECIDED = List.of(
			TimeOffRequest.Status.SUBMITTED, TimeOffRequest.Status.WITHDRAWN);

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		try {
			long removed = employment.deleteByUserId(event.userId());
			long dropped = requests.deleteByUserIdAndStatusIn(event.userId(), UNDECIDED);
			// An open proposal asks a keeper to decide about somebody who is gone. Notices stay: they
			// are the evidence a claim to leave is argued over, pseudonymous like the journal.
			proposals.deleteByUserIdAndStatus(event.userId(), TimeOffProposal.Status.OPEN);
			if (removed > 0 || dropped > 0) {
				log.info("[timeoff] employment dates and {} open requests removed for deleted user {}",
						dropped, event.userId());
			}
		}
		catch (RuntimeException ex) {
			// The account is already gone; a failure here must not turn a completed erasure into a
			// half-finished one that the person is told failed.
			log.warn("[timeoff] could not finish absence erasure for deleted user {}: {}",
					event.userId(), ex.toString());
		}
	}
}
