package com.ahmadre.hinata.moderation.quarantine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One saved version of a removed page. A document each: a hundred versions of a megabyte would
 * not fit the sixteen a single document may hold.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("moderation_quarantine_items")
// The versions of one removal in order, and what makes keeping one twice impossible.
@CompoundIndex(name = "quarantine_number", def = "{'quarantineId': 1, 'number': 1}", unique = true)
public class QuarantinedVersion {

	@Id
	private String id;
	private String quarantineId;
	private int number;
	private String title;
	private String contentDoc;
	private String editorId;
	private Instant savedAt;
}
