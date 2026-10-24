package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.CopySource;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything reported about one piece of content and what was decided (HIN-26, HIN-170). Five
 * reports on the same comment are one case and need one decision.
 *
 * <p>The counters mean one thing each: {@link #reportCount} counts reporting sources, not requests;
 * {@link #categories} and {@link #urgent} describe what was ever reported, so a report corrected
 * from a threat to spam leaves the case urgent. No sum is ever recomputed and written back, and
 * {@link #rev} moves with moderator steps only, never with a report.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("moderation_cases")
@CompoundIndexes({
		// One open case per target. The upsert filter repeats the partial filter word for word,
		// so the planner always picks this index.
		@CompoundIndex(name = "open_target", def = "{'targetKey': 1}", unique = true,
				partialFilter = "{'status': 'OPEN', 'targetKey': {$exists: true}}"),
		// View OPEN: urgent first, then oldest; also the reminder's age range.
		@CompoundIndex(name = "open_queue", def = "{'status': 1, 'urgent': -1, 'firstReportedAt': 1, '_id': 1}"),
		// View DONE, the follow-up re-drive and retention.
		@CompoundIndex(name = "done_queue", def = "{'status': 1, 'decidedAt': -1, '_id': -1}"),
		// View APPEAL (HIN-171).
		@CompoundIndex(name = "appeal_queue", def = "{'appeal.state': 1, 'appeal.filedAt': 1, '_id': 1}"),
		// Cases somebody wrote content for: taken out of counts, the export, erasure. The only array
		// in any index.
		@CompoundIndex(name = "contributor_status", def = "{'contributorIds': 1, 'status': 1}"),
		// Content under a legal hold, asked by every way content is deleted; empty until HIN-171.
		@CompoundIndex(name = "legal_hold", def = "{'target.id': 1, 'legalHoldUntil': 1}",
				partialFilter = "{'legalHoldUntil': {$exists: true}}"),
		// Decided cases that still owe a step, for the follow-up job (HIN-171); unset when settled.
		@CompoundIndex(name = "follow_up_due", def = "{'followUpDue': 1}",
				partialFilter = "{'followUpDue': {$exists: true}}")
})
public class ModerationCase {

	/** Most entries a case's history keeps: the first report and the moderators' steps. */
	public static final int HISTORY_MAX = 50;

	@Id
	private String id;

	/** Where the content lives, always derived on the server (HIN-169). */
	private ContentRef target;

	/** {@code KIND:id}; absent for {@code OTHER}, so every such report is a case of its own. */
	private String targetKey;

	/** As a person names the place: {@code MESSE-14}, or the free text of an {@code OTHER} report. */
	private String location;

	private CaseStatus status;

	/** A threat or child safety was reported on this case at least once. */
	private boolean urgent;

	@Builder.Default
	private Set<ReportCategory> categories = new LinkedHashSet<>();

	@Builder.Default
	private Set<ReportSource> sources = new LinkedHashSet<>();

	/** Distinct reporting sources; repeated automated findings count in {@link ContentReport#getHits()}. */
	private int reportCount;

	/** Everyone who wrote the reported state, from the snapshot: drives the conflict of interest. */
	@Builder.Default
	private Set<String> contributorIds = new LinkedHashSet<>();

	/** Who receives the statement; set by the decision (HIN-171). */
	private String responsibleId;

	private Instant firstReportedAt;
	private Instant lastReportedAt;

	private String assigneeId;
	private Instant claimedAt;

	/** The content as it was at the first report; emptied when no action is taken. */
	private ContentSnapshot evidence;

	/** Reported without an account on internal content: only the report shows until opened. */
	private boolean evidenceWithheld;

	private EvidenceReveal evidenceReveal;

	/** Reports whose source a moderator uncovered, with a reason, for this case (HIN-170). */
	@Builder.Default
	private Set<String> revealedReportIds = new LinkedHashSet<>();

	/** At most 160 characters for the list; null for sensitive categories. */
	private String listExcerpt;

	@Builder.Default
	private List<CaseEvent> history = new ArrayList<>();

	private Instant remindedAt;
	private Instant escalatedAt;

	private Decision decision;

	/** The decision's moment at the top level, for the indexes. */
	private Instant decidedAt;

	private Appeal appeal;

	private RemovalState removalState;

	/** Originals not yet confirmed gone (HIN-169). */
	@Builder.Default
	private List<String> pendingDeletes = new ArrayList<>();

	private Instant copiesScrubbedAt;
	private Instant statementSentAt;
	private Instant reportersNotifiedAt;

	/**
	 * What the copies of the removed words are found by, kept only until they are scrubbed and
	 * then unset (HIN-171): the follow-up job needs it after a restart.
	 */
	private List<CopySource> pendingCopies;

	/** Other content that showed an image the removal moved out of all of them (HIN-169). */
	private List<OtherUse> otherUses;

	/** Since when the case owes a step of its follow-up; unset once it owes none (HIN-171). */
	private Instant followUpDue;

	/** Until when a run of the follow-up job holds the case; a second instance leaves it alone. */
	private Instant followUpUntil;

	/** The decision's removal undone, after an appeal or without one (HIN-171). */
	private Restoration restoration;

	/**
	 * A decision that suspends a person with a role, waiting for a second person to confirm it
	 * (HIN-172). The case stays open meanwhile.
	 */
	private Decision pendingDecision;

	/** The suspension the decision put into effect (HIN-172). */
	private CaseSuspension suspension;

	/** When the notice of the person responsible last changed, and when they were last told of it. */
	private Instant noticeChangedAt;
	private Instant noticeChangeSentAt;

	private Instant retainUntil;
	private Instant legalHoldUntil;
	private Instant purgingAt;

	/** Raised by moderator steps only; what a step's {@code rev} must match. */
	private long rev;

	private Instant createdAt;
	private Instant updatedAt;

	/** {@code KIND:id} of a target, the one open case's key. */
	public static String keyOf(ContentRef target) {
		return target.kind().name() + ":" + target.id();
	}

	/**
	 * Whether the decision restricted anything: content to remove, or a suspension that took effect.
	 * An action whose suspension was refused restricted nothing and owes no statement (HIN-172).
	 */
	public boolean restricted() {
		return decision != null && decision.outcome() == Decision.Outcome.ACTION
				&& (decision.removal() != null || suspension != null);
	}
}
