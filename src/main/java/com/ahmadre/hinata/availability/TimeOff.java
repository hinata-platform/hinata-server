package com.ahmadre.hinata.availability;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Days somebody is away.
 *
 * <p>A type and a span, and nothing that says why. The type is all capacity needs, and a reason
 * for a sick day would be health data (Art. 9 DSGVO). {@link #note} is optional and short, for the
 * person's own orientation; nobody but the person and an administrator reads it.
 *
 * <p>Days, not instants: {@link #from} and {@link #to} are both included, and a half day is a
 * single day with {@link #halfDay}.
 */
@Data
@Builder(toBuilder = true)
@Document("time_off")
@CompoundIndex(name = "user_to_from", def = "{'userId': 1, 'to': 1, 'from': 1}")
// The order every list reads in, forwards or backwards: sorted from the index, not in memory.
@CompoundIndex(name = "user_from_id", def = "{'userId': 1, 'from': 1, '_id': 1}")
public class TimeOff {

	public static final int NOTE_MAX = 200;

	/**
	 * Most absences one person keeps per year, counted by their first day. A week of vacation every
	 * week and every sick day is still below it; a script filing thousands is not.
	 */
	public static final int PER_YEAR_MAX = 100;

	/**
	 * How many years before or after the person's today an absence may be set to. Planning looks
	 * ahead and a little back, and together with {@link #PER_YEAR_MAX} it bounds how many absences
	 * one person can file for the years around now. Absences already filed stay, however old.
	 */
	public static final int YEARS_AROUND_TODAY = 2;

	public enum Type {
		VACATION, SICK, OTHER
	}

	@Id
	private String id;

	private String userId;

	private Type type;

	/**
	 * The operator-defined type this was entered under, when there is one (HIN-116). Null on every
	 * document written before absence management existed, and on every one written while it is off.
	 *
	 * <p>{@link #type} stays the truth a client reads: it is derived from this type's kind on the
	 * way in, so an absence entered under "parental leave" reads as {@code OTHER} to an app that
	 * has never heard of the catalogue, and as parental leave to one that has.
	 */
	private String typeId;

	private LocalDate from;

	private LocalDate to;

	/** Only on a single day. Null on documents that never said. */
	private Boolean halfDay;

	private String note;

	/**
	 * The request this absence was approved from, when it was (HIN-117). Null for one entered
	 * directly, and on every document written before requests existed.
	 *
	 * <p>An absence that came from a request is changed through the request and nowhere else: its
	 * days are booked against a balance, and editing or deleting the absence directly would leave
	 * the booking standing and the request pointing at nothing ({@link TimeOffGate}).
	 */
	private String requestId;

	/** Who entered it: the person, or an administrator. */
	private String createdBy;

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	public boolean isHalfDay() {
		return Boolean.TRUE.equals(halfDay);
	}
}
