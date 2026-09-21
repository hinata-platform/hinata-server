package com.ahmadre.hinata.timeoff;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A suggestion that leave lapse after a long illness, waiting for a person to confirm or dismiss
 * it (HIN-119).
 *
 * <p>Leave that could not be taken because of illness lapses fifteen months after the end of its
 * leave year (EuGH 22.11.2011 – C-214/10 <i>KHS</i>; BAG 20.12.2022 – 9 AZR 245/19 after EuGH
 * C-518/20 and C-727/20). Whether somebody was in fact unable to work all that time is a finding
 * about a person's health and their contract, not arithmetic, so the yearly run only proposes it
 * and a keeper decides, with a reason (Art. 22 DSGVO). Nothing is booked until then.
 */
@Data
@Builder(toBuilder = true)
@Document("time_off_proposals")
// The keeper's list: what is still open, oldest first.
@CompoundIndex(name = "status_created", def = "{'status': 1, 'createdAt': 1}")
// One proposal per person, type and leave year, however often the run looks.
@CompoundIndex(name = "run_key", def = "{'runKey': 1}", unique = true)
public class TimeOffProposal {

	/** Where a proposal stands. */
	public enum Status {
		OPEN,
		/** A keeper confirmed it, with a reason, and the lapse was booked. */
		CONFIRMED,
		/** A keeper dismissed it, with a reason; the days stay. */
		DISMISSED
	}

	/** Longest reason a decision may carry. */
	public static final int REASON_MAX = 1000;

	@Id
	private String id;

	private String userId;

	private String typeId;

	/** The leave year the days come from. */
	private Integer year;

	/** The leave year they sit in now, carried on because nobody could be told in time. */
	private Integer heldIn;

	/** What would lapse, in thousandths of a working day. */
	private Integer milliDays;

	/** Calendar days of sickness in the window the proposal looked at. */
	private Integer sickDays;

	private LocalDate windowFrom;

	private LocalDate windowTo;

	private Status status;

	private Instant createdAt;

	private String decidedBy;

	private Instant decidedAt;

	private String decisionReason;

	/** The ledger row a confirmation booked. */
	private String ledgerId;

	private String runKey;

	public int milliDays() {
		return milliDays == null ? 0 : milliDays;
	}
}
