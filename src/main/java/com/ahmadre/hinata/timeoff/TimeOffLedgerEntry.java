package com.ahmadre.hinata.timeoff;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One movement of one person's balance for one type, in thousandths of a working day.
 *
 * <p><b>The balance is the sum of these and is never stored.</b> A counter would be a second
 * truth: two keepers booking at the same moment would each read it, each add their own, and one
 * of the two amounts would vanish without a trace. Summing an append-only journal has no such
 * moment — and it answers the question a stored number cannot, which is "why do I have 24 rather
 * than 30?".
 *
 * <p>Nothing here is ever edited or deleted. A mistake is corrected by booking its opposite, with
 * {@link #reversalOf} pointing at what it undoes, so the record reads as what happened rather than
 * as what somebody wishes had happened. An entitlement somebody was granted and then lost is a
 * fact about their employment, and Art. 5 Abs. 1 lit. d DSGVO asks for accuracy, not for tidiness.
 *
 * <p>{@link #reason} is free text a keeper writes, and it is where a statutory extra — additional
 * leave for a severely disabled employee under § 208 SGB IX, a reduction during parental leave
 * under § 17 BEEG — is recorded. Deliberately so: the alternative is a field on the person saying
 * why, and a field like that is a special category of personal data (Art. 9 DSGVO) sitting in a
 * project tool forever. A sentence on a booking is visible to the person and their keepers, and
 * it disappears with the booking.
 */
@Data
@Builder(toBuilder = true)
@Document("time_off_ledger")
// One person, one leave year, then the type, then the date. Three equality predicates before the
// sort, and their order among themselves does not change what the index can bound — but putting
// `year` second does: the balance screen sums a person's whole year across every type, and with
// `typeId` in the way that query could bound on the person alone and had to walk every row they
// ever had. HIN-116 performance review.
@CompoundIndex(name = "user_year_type_on_id",
		def = "{'userId': 1, 'year': 1, 'typeId': 1, 'effectiveOn': 1, '_id': 1}")
// Whether a type has any history at all, which is what stands between deleting one and switching
// it off. Also the reach of the yearly run in A4, which walks a type's year.
@CompoundIndex(name = "type_year", def = "{'typeId': 1, 'year': 1}")
public class TimeOffLedgerEntry {

	/** Longest reason a booking may carry. Room for a sentence, not for a case file. */
	public static final int REASON_MAX = 500;

	/**
	 * Why a balance moved. The names travel to clients and into the audit log, so they are a wire
	 * contract: add one, never repurpose one.
	 */
	public enum Kind {
		/** The yearly allowance, granted on the anchor day or monthly. */
		ACCRUAL,
		/** What was left at the end of a leave year, leaving it. */
		CARRYOVER_OUT,
		/** The same amount arriving in the year that follows. */
		CARRYOVER_IN,
		/** A keeper's correction, up or down, with a reason. */
		ADJUSTMENT,
		/** Days taken: an approved absence, booked when it is granted (A2). */
		BOOKED,
		/** Days given back — a cancelled absence, or sickness during vacation (§ 9 BUrlG). */
		RETURNED,
		/** Days that lapsed, and only ever with a documented notice behind them (R12, A4). */
		EXPIRED,
		/** Days paid out when somebody leaves (§ 7 Abs. 4 BUrlG, A4). */
		PAYOUT,
		/** A recalculation after the working week changed (EuGH C-486/08, C-219/14). */
		CONVERSION
	}

	@Id
	private String id;

	private String userId;

	private String typeId;

	/** The leave year the movement belongs to, named by the year its anchor day falls in. */
	private Integer year;

	private Kind kind;

	/**
	 * Signed, in thousandths of a working day: {@code +20000} for twenty days granted,
	 * {@code -500} for a half day taken. Thousandths because half days are ordinary and toggl
	 * allows 0.375 of one — and integers because a balance that drifted by a rounding error would
	 * be a balance somebody argues about.
	 */
	private Integer milliDays;

	/** The day the movement counts from. What the ledger sorts by, and what a year is read up to. */
	private LocalDate effectiveOn;

	/** The absence or request it came from, where there is one. */
	private String refId;

	/** Why, in a keeper's words. Required for an adjustment; empty for the mechanical kinds. */
	private String reason;

	/** Who booked it. A job writes none — nobody decided, a rule ran. */
	private String actorId;

	/** The booking this one takes back, where it takes one back. */
	private String reversalOf;

	@CreatedDate
	private Instant createdAt;

	public int milliDays() {
		return milliDays == null ? 0 : milliDays;
	}
}
