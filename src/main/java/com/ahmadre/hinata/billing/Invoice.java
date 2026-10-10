package com.ahmadre.hinata.billing;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * An invoice or a credit note (HIN-96): a booking record.
 *
 * <p>A draft is a working copy — its lines, recipient and notes can change, and it can go. Issuing
 * gives it the next number of the gapless sequence, freezes it and marks every entry it names as
 * billed ({@code WorkItem.invoiceId}), which is what keeps those entries from changing and from
 * the retention sweep. From then on nothing about it changes. The only way back is a credit note:
 * a second record with the lines negated, numbered from the same sequence, that releases the
 * entries for correction and billing again. Tax law wants both kept (§ 147 AO, § 257 HGB), so
 * nothing here ever deletes an issued record, and a project that has one cannot be deleted.
 *
 * <p>The lines are a snapshot: description, minutes, rate and amount as issued. The entries they
 * were built from may later be corrected after a credit note, and the record must still say what
 * was billed.
 */
@Data
@Builder(toBuilder = true)
@Document("invoices")
// Numbers are unique; drafts have none, so the index holds only issued records.
@CompoundIndex(name = "number", def = "{'number': 1}", unique = true,
		partialFilter = "{'number': {'$exists': true}}")
// The lists: a project's records, or everything, newest first, optionally by status.
@CompoundIndex(name = "project_status_created", def = "{'projectId': 1, 'status': 1, 'createdAt': -1}")
@CompoundIndex(name = "status_created", def = "{'status': 1, 'createdAt': -1}")
public class Invoice {

	public enum Kind {
		INVOICE, CREDIT_NOTE
	}

	/**
	 * {@code ISSUING} is the moment between the claim of a draft and its number: the one state no
	 * second caller can enter, which is what makes two clicks on "issue" one invoice and one
	 * refusal rather than two numbers.
	 */
	public enum Status {
		DRAFT, ISSUING, ISSUED
	}

	/** What one line adds up. */
	public enum Grouping {
		PROJECT, ISSUE, USER, DAY
	}

	/** Who the invoice is addressed to. Free text: billing keeps no customer register. */
	public record Recipient(String name, String address, String vatId) {
	}

	/**
	 * One line.
	 *
	 * @param entryIds the entries the line was built from; empty on a credit note, whose lines
	 *                 point at the invoice they reverse
	 * @param unrated  no rate covered the line's entries; an invoice with such a line is not issued
	 */
	public record Line(String id, String description, String groupKey, List<String> entryIds, long minutes,
			long rateCents, long amountCents, boolean unrated) {
	}

	/** Net, tax and gross, in cents, and the minutes billed. */
	public record Totals(long minutes, long netCents, long taxCents, long grossCents) {

		static final Totals NONE = new Totals(0, 0, 0, 0);
	}

	@Id
	private String id;

	@Builder.Default
	private Kind kind = Kind.INVOICE;

	/** {@code PREFIX-YEAR-NNNNN}; null on a draft. */
	private String number;

	private String projectId;

	private LocalDate periodFrom;

	private LocalDate periodTo;

	@Builder.Default
	private Status status = Status.DRAFT;

	private Grouping grouping;

	private String currency;

	/** The language the invoice's own words are in: the issuer's when it was drafted. */
	private String language;

	private Recipient recipient;

	/** Tax rate in basis points (1900 = 19 %); 0 for none. */
	private int taxBasisPoints;

	private String notes;

	@Builder.Default
	private List<Line> lines = new ArrayList<>();

	@Builder.Default
	private Totals totals = Totals.NONE;

	/** On a credit note: the invoice it reverses. */
	private String creditedInvoiceId;

	/** On an invoice: the credit note that reversed it. */
	private String creditNoteId;

	private Instant issuedAt;

	private String issuedBy;

	private String createdBy;

	private Instant createdAt;

	private String updatedBy;

	private Instant updatedAt;

	@Version
	private Long version;

	public List<Line> getLines() {
		return lines == null ? List.of() : lines;
	}

	public Totals getTotals() {
		return totals == null ? Totals.NONE : totals;
	}

	/** Every entry the lines name. */
	public List<String> entryIds() {
		List<String> ids = new ArrayList<>();
		for (Line line : getLines()) {
			if (line.entryIds() != null) {
				ids.addAll(line.entryIds());
			}
		}
		return ids;
	}
}
