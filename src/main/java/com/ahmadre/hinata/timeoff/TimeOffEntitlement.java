package com.ahmadre.hinata.timeoff;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * That one person was granted one type for one leave year, and how much.
 *
 * <p>The row is the <em>grant</em>; the {@link TimeOffLedgerEntry} it creates is the
 * <em>movement</em>. Keeping both looks redundant for a heartbeat and stops looking redundant the
 * first time somebody asks "was Sam given this year's vacation at all, or did they simply never
 * take any?" — a missing row answers that, a zero balance does not.
 *
 * <p>Unique per person, type and year, so granting twice is a refusal rather than double the days.
 * Correcting the figure afterwards is a booking with a reason, never an edit here: what somebody
 * was originally granted stays legible (§ 2 Abs. 1 S. 2 Nr. 8 NachwG asks an employer to state the
 * annual leave, and a record that quietly rewrites itself cannot evidence what was stated).
 */
@Data
@Builder(toBuilder = true)
@Document("time_off_entitlements")
@CompoundIndex(name = "user_type_year", def = "{'userId': 1, 'typeId': 1, 'year': 1}", unique = true)
// Who was granted what for a year: the admin list, and the reach of the yearly run in A4.
@CompoundIndex(name = "type_year_user", def = "{'typeId': 1, 'year': 1, 'userId': 1}")
public class TimeOffEntitlement {

	/** How far from today a year may be granted, in either direction. */
	public static final int YEARS_AROUND_TODAY = 5;

	/** Where the granted figure came from. */
	public enum Source {
		/** The type's own allowance, taken as it stood when the year was granted. */
		TYPE_DEFAULT,
		/** A figure a keeper typed for this person: a negotiated contract, a statutory extra. */
		INDIVIDUAL
	}

	@Id
	private String id;

	private String userId;

	private String typeId;

	private Integer year;

	/**
	 * What was granted for the year, in thousandths of a working day, before any proration.
	 *
	 * <p>A snapshot, not a pointer: raising a type's allowance in March must not silently rewrite
	 * what people were told in January. The new figure applies to the years granted after it, and
	 * a keeper who wants the old years raised books the difference, which leaves a record of the
	 * decision.
	 */
	private Integer allowanceMilliDays;

	/** What the year actually worked out to after § 4 waiting period and § 5 twelfths. */
	private Integer accruedMilliDays;

	private Source source;

	/**
	 * Why the year worked out the way it did, as {@link TimeOffBalances#accrue} decided at the
	 * moment of the grant.
	 *
	 * <p>Stored rather than re-derived, because it cannot be re-derived: "eight of twenty" is the
	 * same number whether somebody joined in July, left in March or is still inside their waiting
	 * period, and § 5 BUrlG treats those as three different things. Null on a document written
	 * before this field existed, and then the reader falls back to what the numbers still allow.
	 */
	private TimeOffBalances.Reason reason;

	/** Why this person got something other than the type's figure. Free text, optional. */
	private String note;

	private String grantedBy;

	@CreatedDate
	private Instant grantedAt;

	public int allowanceMilliDays() {
		return allowanceMilliDays == null ? 0 : allowanceMilliDays;
	}

	public int accruedMilliDays() {
		return accruedMilliDays == null ? 0 : accruedMilliDays;
	}
}
