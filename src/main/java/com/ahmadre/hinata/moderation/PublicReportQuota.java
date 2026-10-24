package com.ahmadre.hinata.moderation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * How many reports one mailbox sent on one day through the form for reports without an account
 * (HIN-175): counted up in one atomic step, so reports sent at the same moment cannot all pass
 * a limit each of them read before the others were filed. Keyed by the address under the form
 * key, never the address itself; gone two days after the day began.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("public_report_quota")
public class PublicReportQuota {

	/** {@code <hash of the address>:<day>}. */
	@Id
	private String id;

	private Long count;

	@Indexed(name = "quota_ttl", expireAfter = "P2D")
	private Instant createdAt;
}
