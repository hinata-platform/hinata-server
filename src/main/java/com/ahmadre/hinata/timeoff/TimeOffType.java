package com.ahmadre.hinata.timeoff;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.MonthDay;
import java.util.Locale;

/**
 * One kind of absence an operator offers, with the rules that belong to it.
 *
 * <p>Stage 10 had three fixed types — vacation, sick, other — which is all capacity ever needed.
 * An organisation needs more: parental leave that accrues nothing, unpaid leave that needs
 * approval, training days with five a year. So the catalogue is a collection, the way
 * {@code TimeTag} is the catalogue behind an entry's labels.
 *
 * <p><b>The stored absence type does not change.</b> {@code VACATION|SICK|OTHER} in
 * {@code availability.TimeOff} is the storage and wire contract of the documents the frozen store
 * app reads, and every absence still carries one. It is <em>derived</em> from {@link #kind} —
 * {@code VACATION} from {@code VACATION}, {@code SICK} from {@code SICK}, {@code OTHER} from
 * everything else — rather than chosen beside it, so a new type cannot invent a fourth value
 * nobody downstream understands. The mapping itself lives with the one class that writes an
 * absence (A2), not here: a catalogue entry that imported the availability package would drag
 * every reader of this class under the boundary rules that guard capacity.
 *
 * <p><b>{@link #kind} is what cannot be configured away.</b> A type of the {@code SICK} kind is
 * never subject to approval, whatever the rest of the document says (R11, § 5 EFZG): sickness is
 * notified, not applied for. {@link #requiresApproval()} enforces that rather than trusting the
 * stored flag, so a document written by an older version — or by hand — cannot turn a sick note
 * into something somebody has to grant.
 *
 * <p>Every configurable field is an object type with a getter that supplies the default. A
 * document written before a field existed reads as "the default", which is the only behaviour
 * that keeps a collection extensible across stages.
 */
@Data
@Builder(toBuilder = true)
@Document("time_off_types")
public class TimeOffType {

	/** Most types one instance may carry; beyond this a picker stops being a picker. */
	public static final int TYPES_MAX = 50;

	public static final int KEY_MAX = 40;
	public static final int NAME_MAX = 60;

	/** A working day, in thousandths. Half a day is 500; toggl's 0.375 is 375. */
	public static final int DAY = 1_000;

	/** Most days one type may grant in a year: a generous ceiling, not a policy. */
	public static final int ALLOWANCE_MAX_MILLI_DAYS = 366 * DAY;

	/** The three types a fresh instance starts with, and which cannot be deleted. */
	public static final String SYSTEM_VACATION = "vacation";
	public static final String SYSTEM_SICK = "sick";
	public static final String SYSTEM_OTHER = "other";

	/**
	 * What an absence of this type <em>is</em>, as opposed to how it is administered. The kind
	 * decides the two things that are not an operator's to configure: which stored absence type
	 * ({@code VACATION|SICK|OTHER}) it becomes, and whether approval may be required at all.
	 */
	public enum Kind {
		VACATION, SICK, SPECIAL, UNPAID, PARENTAL, TRAINING, COMPENSATORY, OTHER
	}

	/** How an allowance arrives: all at once on the anchor day, monthly, or not at all. */
	public enum Accrual {
		NONE, ANNUAL, MONTHLY
	}

	/** What happens to what is left when the leave year ends. */
	public enum Carryover {
		NONE, UNLIMITED, CAPPED
	}

	/** Who decides a request for this type (A2 routes it; A1 stores the choice). */
	public enum ApproverRule {
		TEAM_LEAD, NAMED, ADMIN, AUTO
	}

	/** How much of an absence of this type other people may see (A3 reads it). */
	public enum Visibility {
		SELF_ONLY, BUSY_ONLY, TYPE
	}

	@Id
	private String id;

	/**
	 * The stable identity, lower-cased and unique. Renaming is a display change; the key is what
	 * an entitlement, a booking and a request point at, and what the system types are recognised
	 * by, so it never changes after creation.
	 */
	@Indexed(unique = true)
	private String key;

	/**
	 * What people see. Free text in the operator's own language.
	 *
	 * <p>Null on a system type that nobody has renamed, and that is a value rather than a gap: the
	 * client renders the built-in label for {@link #systemKey}, translated like every other string
	 * in the product. Shipping "Vacation" as data would leave a German instance reading English
	 * until somebody edited three rows. The moment an operator types a name, theirs wins.
	 */
	private String name;

	/** A Lucide icon name from the allow-list in {@link TimeOffIcons}. */
	private String icon;

	/** Hue on the colour wheel, 0–359, the vocabulary tags and labels already use. */
	private Integer hue;

	private Kind kind;

	/** Whether the days are paid. Display and reporting only; hinata computes no money. */
	private Boolean paid;

	/** Whether taking a day reduces a balance. False for anything with no allowance. */
	private Boolean countsAgainstBalance;

	/** No allowance, no balance: days are recorded and never counted against anything. */
	private Boolean unlimited;

	/** Whether a request has to be granted. Ignored for {@link Kind#SICK} — see {@link #requiresApproval()}. */
	private Boolean approvalRequired;

	private ApproverRule approverRule;

	/** Whether half days may be requested. */
	private Boolean halfDaysAllowed;

	/** Whether arbitrary fractions of a day may be requested (toggl allows 0.2, 0.3, 0.375). */
	private Boolean fractionAllowed;

	/** How many days ahead a request should be filed. A warning in A2, never a refusal. */
	private Integer minNoticeDays;

	/** Longest single absence of this type, or null for no limit. */
	private Integer maxConsecutiveDays;

	/** Whether a balance may go below zero — somebody who negotiated extra days, say. */
	private Boolean negativeBalanceAllowed;

	/** How far below zero, in thousandths of a day. Null means no floor beyond zero. */
	private Integer negativeLimitMilliDays;

	private Visibility visibility;

	private Accrual accrual;

	/** The yearly allowance in thousandths of a day: 30 days is 30000. */
	private Integer allowanceMilliDays;

	/** Month of the leave year's first day; 1 with {@link #yearAnchorDay} 1 is the calendar year. */
	private Integer yearAnchorMonth;

	private Integer yearAnchorDay;

	/** § 4 BUrlG: full entitlement only after this many months of employment. */
	private Integer waitingPeriodMonths;

	/** § 5 BUrlG: a twelfth per full month for somebody who joined during the year. */
	private Boolean prorateOnJoin;

	/** The same for somebody who leaves during it. */
	private Boolean prorateOnLeave;

	private Carryover carryover;

	/** The cap for {@link Carryover#CAPPED}, in thousandths of a day. */
	private Integer carryoverCapMilliDays;

	/** § 7 Abs. 3 BUrlG: carried days expire on this day of the following year — 31 March by default. */
	private Integer carryoverExpiresMonth;

	private Integer carryoverExpiresDay;

	/** A type switched off is kept and no longer offered; history keeps pointing at it. */
	private Boolean active;

	/**
	 * Set on the three types a fresh instance creates. They can be renamed, recoloured and
	 * reconfigured, but not deleted: an instance with no vacation type is a worse state to leave
	 * somebody in than one with a type they do not use.
	 */
	private String systemKey;

	private String createdBy;

	private String updatedBy;

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	// --- what the rest of the module reads -----------------------------------

	/** The identity of a key: trimmed and lower-cased, or null for a blank. */
	public static String normalizeKey(String key) {
		if (key == null) {
			return null;
		}
		// Locale.ROOT, not the request's: a Turkish reader lower-casing "I" gets "ı", and the same
		// key would have two identities depending on who saved it last.
		String trimmed = key.trim().toLowerCase(Locale.ROOT);
		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * Whether a request of this type needs a decision.
	 *
	 * <p>Always false for {@link Kind#SICK}, whatever is stored. § 5 EFZG gives an employee a duty
	 * to notify, not to ask, and a product with a "reject" button beside a sick note would invite
	 * exactly the conversation the law does not allow (R11). The write path refuses to store the
	 * flag for this kind; this getter makes a document that carries it anyway harmless.
	 */
	public boolean requiresApproval() {
		return kind != Kind.SICK && Boolean.TRUE.equals(approvalRequired);
	}

	public boolean isUnlimited() {
		return Boolean.TRUE.equals(unlimited);
	}

	public boolean isActive() {
		return !Boolean.FALSE.equals(active);
	}

	public boolean isSystem() {
		return systemKey != null && !systemKey.isBlank();
	}

	/** Whether taking a day of this type moves a balance at all. */
	public boolean countsAgainstBalance() {
		return !isUnlimited() && Boolean.TRUE.equals(countsAgainstBalance);
	}

	public boolean negativeBalanceAllowed() {
		return Boolean.TRUE.equals(negativeBalanceAllowed);
	}

	public boolean prorateOnJoin() {
		return !Boolean.FALSE.equals(prorateOnJoin);
	}

	public boolean prorateOnLeave() {
		return !Boolean.FALSE.equals(prorateOnLeave);
	}

	public Accrual accrual() {
		return accrual == null ? Accrual.NONE : accrual;
	}

	public Carryover carryover() {
		return carryover == null ? Carryover.NONE : carryover;
	}

	/**
	 * Who decides a request of this type, and {@link ApproverRule#ADMIN} where nobody chose.
	 *
	 * <p>The administrators are the default rather than the team leads, and that is a security
	 * decision rather than a taste one: in hinata <b>anybody may create a team and add anybody to
	 * it</b>, without the person's consent. Routing leave to "the admins of this person's teams"
	 * by default would therefore let any account make itself the approver of any other account's
	 * statutory leave — and read the note, the balance warning and the history that come with it.
	 * HIN-91 learned the same lesson about who may <em>see</em> an absence; this is the other half
	 * of it (see {@code AvailabilityAccess}).
	 *
	 * <p>{@link ApproverRule#TEAM_LEAD} stays on offer, because on an instance whose teams are the
	 * real organisation it is the right answer. Picking it is then an operator's decision, made in
	 * a screen that says what it means.
	 */
	public ApproverRule approverRule() {
		return approverRule == null ? ApproverRule.ADMIN : approverRule;
	}

	public Visibility visibility() {
		return visibility == null ? Visibility.SELF_ONLY : visibility;
	}

	public int allowanceMilliDays() {
		return allowanceMilliDays == null ? 0 : allowanceMilliDays;
	}

	public int waitingPeriodMonths() {
		return waitingPeriodMonths == null ? 0 : waitingPeriodMonths;
	}

	/** The first day of a leave year. The calendar year unless an operator says otherwise. */
	public MonthDay yearAnchor() {
		return monthDay(yearAnchorMonth, yearAnchorDay, MonthDay.of(1, 1));
	}

	/** The day carried-over days expire on, in the year after the one they came from. */
	public MonthDay carryoverExpiresOn() {
		return monthDay(carryoverExpiresMonth, carryoverExpiresDay, MonthDay.of(3, 31));
	}

	private static MonthDay monthDay(Integer month, Integer day, MonthDay fallback) {
		if (month == null || day == null) {
			return fallback;
		}
		try {
			return MonthDay.of(month, day);
		}
		catch (java.time.DateTimeException invalid) {
			// A stored 31 February cannot throw at read time: this is on the path of every
			// balance, and a job that stopped because a document is odd would be worse than one
			// that falls back to the documented default and lets the next save fix it.
			return fallback;
		}
	}
}
