package com.ahmadre.hinata.timeoff;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * That one person was told how much leave they still have and on which day it lapses (HIN-119).
 *
 * <p><b>This is the evidence without which no leave lapses.</b> Since BAG 19.02.2019 – 9 AZR
 * 541/15 (after EuGH C-684/16 <i>Max-Planck</i> and C-619/16 <i>Kreuziger</i>) leave lapses at
 * the end of the year or of the carryover period only if the employer asked the employee, in good
 * time and in clear words, to take it, and said what happens otherwise. The yearly run books a
 * lapse only when a row here says that happened, and the person reads the same rows in their own
 * list: the operator and the employee hold the same record.
 *
 * <p>Never deleted by a retention rule, and kept when an account goes: the limitation period of a
 * claim to leave starts only with the notice (BAG 20.12.2022 – 9 AZR 266/20), so the record of it
 * is what that claim is argued over. The audit entry written with it stays for the same reason.
 */
@Data
@Builder(toBuilder = true)
@Document("time_off_notices")
// Whether somebody was told about a year's leave before a given day: the question every lapse asks.
@CompoundIndex(name = "user_type_year_sent", def = "{'userId': 1, 'typeId': 1, 'year': 1, 'sentAt': 1}")
// A person's own list, newest first.
@CompoundIndex(name = "user_sent", def = "{'userId': 1, 'sentAt': -1}")
// The scheduled notices go out once each; a second instance collides here.
@CompoundIndex(name = "run_key", def = "{'runKey': 1}", unique = true,
		partialFilter = "{'runKey': {$exists: true}}")
public class TimeOffNotice {

	/** When the notice went out. */
	public enum Kind {
		/** The yearly reminder on the notice day, by default 1 October. */
		ANNUAL,
		/** Weeks before the end of the leave year, for what the end would take. */
		BEFORE_YEAR_END,
		/** Weeks before the carryover deadline, for what was carried in and is still untaken. */
		BEFORE_CARRYOVER_DEADLINE,
		/** Sent by a keeper by hand, from the list of people nobody told yet. */
		MANUAL
	}

	@Id
	private String id;

	private String userId;

	private String typeId;

	/** The leave year the days come from: the year whose leave the notice is about. */
	private Integer year;

	private Kind kind;

	/** What was still there when the notice went out, in thousandths of a working day. */
	private Integer remainingMilliDays;

	/** The day the notice named: after it, what is left lapses. */
	private LocalDate expiresOn;

	private Instant sentAt;

	/** Who sent it by hand; null for the scheduled ones. */
	private String actorId;

	/** Present on the scheduled notices only, which exist once each. */
	private String runKey;

	public int remainingMilliDays() {
		return remainingMilliDays == null ? 0 : remainingMilliDays;
	}
}
