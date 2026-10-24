package com.ahmadre.hinata.moderation.rules;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * That a person accepted a version of the house rules, and when (HIN-175). No address and no
 * device: the version and the moment are what it has to prove. Part of the person's data export
 * and gone with the account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("rules_acceptances")
@CompoundIndex(name = "user_version", def = "{'userId': 1, 'version': 1}", unique = true)
public class RulesAcceptance {

	@Id
	private String id;

	private String userId;

	private Integer version;

	private Instant acceptedAt;
}
