package com.ahmadre.hinata.moderation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A form token of the page for reports without an account that was used (HIN-175): so it cannot
 * be used twice, and so the limits per address and in total count in the database, the same on
 * every instance. Gone two hours after its use, which is as long as a token lives anyway.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("public_form_tokens")
@CompoundIndex(name = "ip_used", def = "{'ipHash': 1, 'usedAt': -1}")
public class PublicFormToken {

	@Id
	private String id;

	/** Of the token; never the token itself. */
	@Indexed(unique = true)
	private String tokenHash;

	/** Of the address the form was sent from, keyed so that it says nothing without the server. */
	private String ipHash;

	@Indexed(name = "used_ttl", expireAfter = "PT2H")
	private Instant usedAt;
}
