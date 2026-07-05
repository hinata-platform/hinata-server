package com.ahmadre.hinata.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * A user's personalisation of their dashboard. One document per user (keyed by
 * user id). Empty {@code projectIds}/{@code teamIds} mean "all" (the default,
 * unscoped view); {@code boardId} pins the hero to a specific board (else the
 * server auto-picks); {@code hiddenCards} are the card keys the user collapsed.
 */
@Document("dashboard_prefs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardPrefs {

	@Id
	private String userId;

	/** Pinned hero board id; {@code null}/blank ⇒ auto-pick. */
	private String boardId;

	/** Scope for aggregated data; empty ⇒ all visible projects. */
	@Builder.Default
	private List<String> projectIds = new ArrayList<>();

	/** Scope for the team ranking; empty ⇒ all teams. */
	@Builder.Default
	private List<String> teamIds = new ArrayList<>();

	/** Card keys the user hid (e.g. {@code gitActivity}, {@code tracker}). */
	@Builder.Default
	private List<String> hiddenCards = new ArrayList<>();
}
