package com.ahmadre.hinata.timetracking;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Locale;

/**
 * One label an entry can carry, as a document of its own.
 *
 * <p>The tags on a {@link WorkItem} are still plain strings — that is the stored
 * shape the published app writes and reads, and it does not change here. This
 * collection is the <em>catalogue</em> beside it: what the picker offers, what
 * {@code limitTagAccess} restricts writing to, and the row a rename or a delete
 * can hang off. Without it, "rename a tag" would have no subject: a string that
 * appears on two thousand entries and nowhere else cannot be renamed, only
 * search-and-replaced by whoever remembers to.
 *
 * <p>Two names that differ only in case are one tag. People type "Meeting" and
 * "meeting" on the same day and mean the same thing, and a report that splits
 * them into two rows is wrong in a way nobody notices until the totals are
 * quoted. {@link #normalized} is the identity — unique, lower-cased — and
 * {@link #name} is what is shown and what is written onto entries.
 */
@Data
@Builder(toBuilder = true)
@Document("time_tags")
public class TimeTag {

	/** Longest a tag may be, matching the per-element cap on an entry's tags. */
	public static final int MAX_NAME = 40;

	@Id
	private String id;

	/** As typed, as shown, and as written onto every entry that carries it. */
	private String name;

	/**
	 * The identity: {@link #name} lower-cased. Unique, so two people cannot
	 * create the same tag under different capitalisation from two devices — the
	 * loser gets a 409 rather than a duplicate the reports would split.
	 */
	@Indexed(unique = true)
	private String normalized;

	/** Hue on the colour wheel, 0–359, the same vocabulary project labels use. */
	private int hue;

	/** Who created it. Kept as a pseudonym after that account is deleted. */
	private String createdBy;

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	/** The identity of a tag name: trimmed and lower-cased, or null for a blank. */
	public static String normalize(String name) {
		if (name == null) {
			return null;
		}
		String trimmed = name.trim();
		// Locale.ROOT, not the request's: a Turkish reader lower-casing "I" gets
		// "ı", and the same tag would then have two identities depending on who
		// saved it last.
		return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
	}
}
