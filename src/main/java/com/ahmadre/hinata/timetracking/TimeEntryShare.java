package com.ahmadre.hinata.timetracking;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * An invitation to copy one entry into somebody else's own record (HIN-95).
 *
 * <p>The invitation is the whole of what the recipient sees before they answer: who asked,
 * and the {@link Snapshot} of the entry taken when they were asked. Never the entry itself,
 * which stays the sender's; accepting files a copy in the recipient's account through the
 * same checks as any entry they write, and the two records go their own ways from there.
 * A shared record would put the locks, approvals and deletions of two people on one
 * document.
 *
 * <p>The snapshot is also what is copied. The sender may change or delete the entry after
 * asking, and the recipient accepts what they were shown; deleting the entry withdraws the
 * invitations still open.
 */
@Data
@Builder(toBuilder = true)
@Document("time_entry_shares")
// One invitation per entry and person: what makes sharing twice a no-op.
@CompoundIndex(name = "entry_to", unique = true, def = "{'entryId': 1, 'toUserId': 1}")
// The recipient's open invitations, newest first: equality on the person and the status,
// then the sort keys, so a page is one bounded index walk.
@CompoundIndex(name = "to_status_created", def = "{'toUserId': 1, 'status': 1, 'createdAt': -1, '_id': -1}")
// What the sender has asked, newest first, in every state.
@CompoundIndex(name = "from_created", def = "{'fromUserId': 1, 'createdAt': -1, '_id': -1}")
public class TimeEntryShare {

	public enum Status {
		/** Waiting for the recipient. */
		PENDING,
		/** The recipient filed a copy. */
		ACCEPTED,
		/** The recipient said no. Nobody is told why, and the sender is not notified. */
		DECLINED,
		/** The sender took the invitation back, or deleted the entry, before an answer. */
		REVOKED
	}

	@Id
	private String id;

	/** The sender's entry. Never handed to the recipient. */
	private String entryId;

	private String fromUserId;

	private String toUserId;

	/** The entry's project when it was shared; an entry without one cannot be shared. */
	private String projectId;

	@Builder.Default
	private Status status = Status.PENDING;

	private Instant createdAt;

	private Instant decidedAt;

	/** The recipient's copy, once they accepted. */
	private String copyId;

	private Snapshot entry;

	/**
	 * The entry as it was when it was shared: what the invitation shows and what an
	 * acceptance copies. Without the sender's id, the entry's id and its history.
	 */
	@Data
	@Builder
	public static class Snapshot {
		private String issueId;
		private LocalDate date;
		private Integer durationMinutes;
		private Instant startedAt;
		private Instant endedAt;
		private String activityType;
		private String description;
		private Boolean billable;
		@Builder.Default
		private List<String> tags = new ArrayList<>();

		/** Never null: a snapshot written without tags has none. */
		public List<String> getTags() {
			return tags == null ? List.of() : tags;
		}

		static Snapshot of(WorkItem item) {
			return Snapshot.builder()
					.issueId(item.getIssueId())
					.date(item.getDate())
					.durationMinutes(item.getDurationMinutes())
					.startedAt(item.getStartedAt())
					.endedAt(item.getEndedAt())
					.activityType(item.getActivityType())
					.description(item.getDescription())
					.billable(item.isBillable())
					.tags(new ArrayList<>(item.getTags()))
					.build();
		}
	}
}
