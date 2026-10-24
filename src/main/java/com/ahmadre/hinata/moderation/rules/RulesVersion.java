package com.ahmadre.hinata.moderation.rules;

import com.ahmadre.hinata.setup.ServerSettings.RulesMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * One version of the house rules (HIN-175), written once and never changed: what an acceptance
 * and a decision point at, so that later anybody can see which words a person agreed to and
 * which rules a decision applied. A new one appears whenever what the rules say changes, the
 * section on how moderation works included.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("moderation_rules_versions")
public class RulesVersion {

	@Id
	private String id;

	/** 1, 2, 3 and so on; unique, so two instances cannot both write the next one. */
	@Indexed(unique = true)
	private Integer version;

	private RulesMode mode;

	/** The instance's own Markdown, for {@code TEXT}. */
	private String text;

	/** The instance's own page, for {@code URL}. */
	private String url;

	/** The rules as they read at the time, with the section on how moderation works, by language tag. */
	private Map<String, String> rendered;

	/** Of {@link #rendered}: a save that changes nothing a reader sees makes no new version. */
	private String fingerprint;

	/** Whether everybody has to accept this version anew; the first one asks everybody. */
	private Boolean requiresReacceptance;

	private Instant createdAt;

	/** Who saved the change; null for the server itself, such as the first version after an update. */
	private String createdBy;

	/** The rules in [language], or in English when there are none in it. */
	public String renderedIn(String language) {
		String text = rendered == null ? null : rendered.get(language);
		return text != null ? text : rendered == null ? "" : rendered.getOrDefault("en", "");
	}
}
