package com.ahmadre.hinata.billing;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One hourly rate for one target over one span of days (HIN-96).
 *
 * <p>Rates are historised, never overwritten: a change from a date on is a new rate whose
 * {@code validFrom} closes the previous one the day before, a planned change is a rate that starts
 * in the future, and a correction backwards is a rate with an earlier start. A report values every
 * entry with the rate in force on the entry's day, never the report's.
 *
 * <p>The target is {@code (scope, scopeId, secondaryId)}: a team, a project, a person, a person
 * within one project ({@code scopeId} the project, {@code secondaryId} the person), an issue, or
 * the instance default with neither id. {@code projectId} is the project a lead's permission is
 * decided by — the project itself, the project of a member rate, the project of the issue when
 * the rate was written — and null for the rates only an administrator writes.
 */
@Data
@Builder(toBuilder = true)
@Document("billing_rates")
// What the resolver and the overlap check ask: one target's rates in order of their start.
@CompoundIndex(name = "kind_scope_target_from",
		def = "{'kind': 1, 'scope': 1, 'scopeId': 1, 'secondaryId': 1, 'validFrom': 1}")
// A lead's list: the rates of the projects they lead.
@CompoundIndex(name = "project_kind", def = "{'projectId': 1, 'kind': 1}",
		partialFilter = "{'projectId': {'$exists': true}}")
public class BillingRate {

	/** Whether a rate prices work for the client or says what it cost. */
	public enum Kind {
		/** What an hour is billed at: revenue, from billable entries only. */
		BILLABLE,
		/** What an hour costs: labour cost, from every entry. Administrators only. */
		COST
	}

	/** What a rate is set for, from the most specific to the most general. */
	public enum Scope {
		ISSUE, PROJECT_MEMBER, USER, PROJECT, TEAM, DEFAULT;

		/** Whether a project lead may set a revenue rate here (for a project they lead). */
		boolean leadWritable() {
			return this == ISSUE || this == PROJECT_MEMBER || this == PROJECT;
		}
	}

	@Id
	private String id;

	private Kind kind;

	private Scope scope;

	/** The team, project, person or issue; null for {@link Scope#DEFAULT}. */
	private String scopeId;

	/** The person of a {@link Scope#PROJECT_MEMBER} rate; null otherwise. */
	private String secondaryId;

	/** The project a lead's permission is judged by; null for team, person and default rates. */
	private String projectId;

	/** Per hour, in the smallest unit of {@link #currency} (cents). */
	private long amountCents;

	/** ISO 4217, the instance currency when the rate was written. */
	private String currency;

	/** First day the rate is in force. */
	private LocalDate validFrom;

	/** Last day the rate is in force, inclusive; null while it runs on. */
	private LocalDate validTo;

	private String createdBy;

	private Instant createdAt;

	private String updatedBy;

	private Instant updatedAt;

	@Version
	private Long version;

	/** Whether [day] lies inside the rate's span, both ends included. */
	public boolean covers(LocalDate day) {
		return day != null && !day.isBefore(validFrom) && (validTo == null || !day.isAfter(validTo));
	}

	/** Whether this span and [from, to] share a day; a null end runs on. */
	public boolean overlaps(LocalDate from, LocalDate to) {
		boolean startsBeforeOtherEnds = to == null || !validFrom.isAfter(to);
		boolean endsAfterOtherStarts = validTo == null || !validTo.isBefore(from);
		return startsBeforeOtherEnds && endsAfterOtherStarts;
	}
}
