package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ReportCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One source reporting one case (HIN-170): a member, somebody without an account, or the text check.
 * A second report by the same source replaces the category and the explanation of its first and
 * counts nothing twice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("moderation_reports")
@CompoundIndexes({
		// One report per source and case: a member, "auto:<field>" or "public:<id>".
		@CompoundIndex(name = "case_reporter", def = "{'caseId': 1, 'reporterKey': 1}", unique = true),
		// The reports of a case, a page at a time.
		@CompoundIndex(name = "case_created", def = "{'caseId': 1, 'createdAt': 1, '_id': 1}"),
		// Limits per person, answered from the index; the personal data export.
		@CompoundIndex(name = "reporter_created", def = "{'reporterId': 1, 'createdAt': -1, 'category': 1}",
				partialFilter = "{'reporterId': {$exists: true}}"),
		// Comments a person reported and that are still there, for folding them away (HIN-173).
		// Equality, sort, then the covered fields.
		@CompoundIndex(name = "reporter_hidden",
				def = "{'reporterId': 1, 'targetKind': 1, 'removed': 1, 'createdAt': -1, 'targetId': 1}",
				partialFilter = "{'reporterId': {$exists: true}}"),
		// Each source's child-safety reports, for its count of unfounded ones on the case page;
		// answered from the index alone, which holds those few reports and nothing else.
		@CompoundIndex(name = "child_safety_reporter", def = "{'reporterKey': 1, 'caseId': 1}",
				partialFilter = "{'category': 'CHILD_SAFETY'}")
})
public class ContentReport {

	public static final int EXPLANATION_MAX_MEMBER = 1000;
	public static final int EXPLANATION_MAX_PUBLIC = 4000;

	@Id
	private String id;

	private String caseId;

	/** The member's id, {@code auto:<field>} or {@code public:<id>}. */
	private String reporterKey;

	/** Absent for reports without an account and for the text check. */
	private String reporterId;

	/** Reports without an account only (HIN-175). */
	private Notifier notifier;

	private ReportSource source;
	private ReportCategory category;
	private String explanation;

	/** The reporter's statement that they report in good faith (DSA Art. 16(2)(d)). */
	private boolean goodFaith;

	/** Copied from the case, for folding reported comments away (HIN-173). */
	private ContentKind targetKind;
	private String targetId;

	/** Set when the case removed the content, for HIN-173. */
	private boolean removed;

	/** Repeated automated findings (HIN-176). */
	private int hits;
	private Instant lastSeenAt;

	private Instant createdAt;
	private Instant updatedAt;
	private Instant decisionNoticeSentAt;
}
