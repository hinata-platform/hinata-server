package com.ahmadre.hinata.timeoff;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * When somebody joined, and when they left. The only personal facts this module keeps beyond the
 * absences themselves.
 *
 * <p>They are here because three statutory rules cannot be computed without them: the waiting
 * period (§ 4 BUrlG), twelfths for a part year (§ 5), and the settlement owed when an employment
 * ends (§ 7 Abs. 4). Everything else an HR system knows — contract, salary, role, disability,
 * parental leave — is deliberately absent. A project tool that started holding those would be
 * holding special categories of personal data (Art. 9 DSGVO) forever, to answer questions nobody
 * asked it.
 *
 * <p>Read and written by keepers only, never by leads, and deleted with the account: once there is
 * nobody left to compute an entitlement for, the dates have no purpose (Art. 5 Abs. 1 lit. e).
 * What stays is the journal — what was granted and taken is the record, and it keeps the person's
 * id as a pseudonym the way a work item does.
 */
@Data
@Builder(toBuilder = true)
@Document("time_off_employment")
public class TimeOffEmployment {

	/** Longest note a keeper may attach — a contract reference, not a personnel file. */
	public static final int NOTE_MAX = 200;

	@Id
	private String id;

	@Indexed(unique = true)
	private String userId;

	/** The first day of the employment, as the entitlement arithmetic reads it. */
	private LocalDate hiredOn;

	/** The last day, once it is known. Null for somebody still employed. */
	private LocalDate leftOn;

	/** Optional, short, and a keeper's words. */
	private String note;

	private String updatedBy;

	private Instant updatedAt;
}
