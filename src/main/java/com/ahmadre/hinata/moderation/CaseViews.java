package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ReportCategory;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** What moderators read about cases (HIN-170). The server derives every state; the app counts nothing. */
public final class CaseViews {

	private CaseViews() {
	}

	/** One state per case, the one the list shows and the app words and draws (HIN-174). */
	public enum CaseState {
		OPEN,
		URGENT,
		IN_REVIEW,
		/** Decided to suspend a person with a role, waiting for a second person (HIN-172). */
		PENDING_CONFIRMATION,
		APPEAL,
		ACTED,
		NO_ACTION
	}

	/** The three lists of the moderation page. */
	public enum View {
		OPEN,
		APPEAL,
		DONE
	}

	/** A person as a case names them: embedded, so the page needs no second list of people. */
	public record PersonRef(String id, String displayName, String avatarUrl) {
	}

	/** One row of a list. Built from the case alone, never from the content it is about. */
	public record CaseSummary(String id, CaseState state, boolean urgent, ContentKind kind,
			Set<ReportCategory> categories, Set<ReportSource> sources, int reportCount, Instant firstReportedAt,
			String location, String listExcerpt, PersonRef assignee, boolean reportedByMe,
			Decision.Outcome outcome, boolean removed, boolean suspended, long rev) {
	}

	/**
	 * One report as moderators see it: numbered, by kind of source, covered until somebody
	 * uncovers it with a reason.
	 *
	 * @param unfoundedChildSafetyBefore how many earlier child-safety reports of the same source
	 *                                   were decided as manifestly unfounded; only the number
	 */
	public record ReportView(String id, int number, ReportSource source, ReportCategory category,
			String explanation, Instant createdAt, PersonRef reporter, int unfoundedChildSafetyBefore) {
	}

	/**
	 * A case opened: what was reported, how it stands now while it is open, and what happened.
	 *
	 * @param removalState whether the decided removal is done; {@code INCOMPLETE} while the store
	 *                     still holds originals or the removal is being tried again
	 * @param otherUses    other content that showed an image the removal moved out of all of them
	 */
	public record CaseDetail(CaseSummary summary, ContentSnapshot evidence, ContentSnapshot current,
			boolean evidenceWithheld, EvidenceReveal evidenceReveal, List<ReportView> reports, long reportTotal,
			List<CaseEvent> history, Instant claimedAt, Decision decision, Appeal appeal, Restoration restoration,
			RemovalState removalState, List<OtherUse> otherUses) {
	}

	/** The numbers on the page's pills, each one a count of a list minus what the reader wrote. */
	public record Summary(long open, long urgent, long overdue, long appeals) {
	}
}
