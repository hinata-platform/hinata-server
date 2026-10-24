package com.ahmadre.hinata.moderation.quarantine;

import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.RemovalScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What one removal took (HIN-169): the fields as they were and the objects it copied. Kept for as
 * long as the case is; it has no deadline of its own, so a legal hold on the case always holds it
 * too. Versions of a page are kept beside it in {@link QuarantinedVersion}, one per document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("moderation_quarantine")
// Everything a case removed, for the evidence view, for restoring and for retention.
@CompoundIndex(name = "case", def = "{'caseId': 1, '_id': 1}")
public class QuarantinedContent {

	@Id
	private String id;
	private String caseId;
	private ContentRef ref;
	private RemovalScope scope;

	/** The removed fields, current state only; a document's text as its Lexical JSON. */
	private Map<String, Object> fields;
	private List<QuarantinedObject> objects;
	private Instant createdAt;
}
