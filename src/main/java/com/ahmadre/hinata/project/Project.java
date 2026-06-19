package com.ahmadre.hinata.project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Document("projects")
public class Project {

	@Id
	private String id;

	/** Short uppercase key used for issue numbering, e.g. "ASTA" -> ASTA-42. */
	@Indexed(unique = true)
	private String key;

	@TextIndexed(weight = 10)
	private String name;

	@TextIndexed(weight = 2)
	private String description;

	/** User id of the primary project lead; mirrors {@code leadIds.get(0)}. */
	private String leadId;

	/** Project leads (>= 1). The first entry is the primary lead. */
	@Builder.Default
	private List<String> leadIds = new ArrayList<>();

	@Builder.Default
	private List<String> memberIds = new ArrayList<>();

	/** Ordered workflow states (>= 2); doubles as default agile board columns.
	 * The state {@code name} is the canonical key issues reference; the stable
	 * {@code id} is used to detect renames and as a UI list key. */
	@Builder.Default
	private List<WorkflowState> workflowStates = defaultWorkflow();

	/** States counted as "done", by state <em>name</em> (>= 1). Kept as names so
	 * issue/dashboard/report queries can match {@code Issue.state} directly. */
	@Builder.Default
	private List<String> resolvedStates = new ArrayList<>(DEFAULT_RESOLVED);

	/** Reusable, colored issue labels ("Stichworte"). Grows automatically as
	 * issues are tagged; the assignable vocabulary surfaced by the app. */
	@Builder.Default
	private List<Label> labels = new ArrayList<>();

	/** Accent color (hex) used by the app, following the pastel design system. */
	@Builder.Default
	private String color = "#AEC6F4";

	@Builder.Default
	private boolean archived = false;

	/** Monotonic counter backing per-project issue numbers. */
	@Builder.Default
	private long issueCounter = 0;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	/** Ordered workflow state names — what issues/boards key off of. */
	public List<String> workflowStateNames() {
		List<String> names = new ArrayList<>();
		if (workflowStates != null) {
			for (WorkflowState s : workflowStates) names.add(s.getName());
		}
		return names;
	}

	/** Reusable label names. */
	public List<String> labelNames() {
		List<String> names = new ArrayList<>();
		if (labels != null) {
			for (Label l : labels) names.add(l.getName());
		}
		return names;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Label {
		private String id;
		private String name;
		private int hue;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class WorkflowState {
		private String id;
		private String name;
		private int hue;
	}

	/** Generates a short stable id for a workflow state / label. */
	public static String newId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	// Default workflow per the product spec (names + oklch hues).
	private static final List<String> DEFAULT_STATE_NAMES =
			List.of("Backlog", "Open", "In Progress", "In Review", "Done");
	private static final List<Integer> DEFAULT_STATE_HUES = List.of(255, 250, 70, 300, 155);
	public static final List<String> DEFAULT_RESOLVED = List.of("Done");

	/** A fresh default workflow (new ids each call). */
	public static List<WorkflowState> defaultWorkflow() {
		List<WorkflowState> states = new ArrayList<>();
		for (int i = 0; i < DEFAULT_STATE_NAMES.size(); i++) {
			states.add(WorkflowState.builder()
					.id(newId())
					.name(DEFAULT_STATE_NAMES.get(i))
					.hue(DEFAULT_STATE_HUES.get(i))
					.build());
		}
		return states;
	}

	/** Default hue for a state by its (case-insensitive) name; used by the boot
	 * migration when upgrading legacy string-only workflow arrays. */
	public static int defaultHueForState(String name) {
		if (name != null) {
			for (int i = 0; i < DEFAULT_STATE_NAMES.size(); i++) {
				if (DEFAULT_STATE_NAMES.get(i).equalsIgnoreCase(name.trim())) {
					return DEFAULT_STATE_HUES.get(i);
				}
			}
		}
		return 250; // neutral indigo fallback
	}

	/** Distinct, evenly-spread label hues (matches the Flutter/HTML palette). */
	public static final List<Integer> LABEL_HUES = List.of(70, 250, 300, 200, 155, 20, 330, 45);

	public static int labelHueAt(int index) {
		return LABEL_HUES.get(Math.floorMod(index, LABEL_HUES.size()));
	}
}
