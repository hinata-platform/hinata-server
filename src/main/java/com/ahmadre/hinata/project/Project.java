package com.ahmadre.hinata.project;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

	/**
	 * Per-project Git repository connection + development automation. {@code null}
	 * until a repository is linked in project settings. Git integration is strictly
	 * per project: each project owns its provider + repo and its own automation
	 * rules, expressed against <em>this</em> project's {@link WorkflowState} ids.
	 * See {@code com.ahmadre.hinata.git}.
	 */
	private Git git;

	/**
	 * Additional connected repositories beyond the primary {@link #git}. A project
	 * can track work across several repos (e.g. an app + a server). Each entry is a
	 * full connection with its own identity, token and webhook, but automation
	 * rules + branch template are shared and read from the primary {@code git}.
	 */
	@Builder.Default
	private List<Git> extraRepos = new ArrayList<>();

	/** Every connected repository, primary first (empty when none is linked). */
	@JsonIgnore
	public List<Git> allRepos() {
		List<Git> all = new ArrayList<>();
		if (git != null) all.add(git);
		if (extraRepos != null) all.addAll(extraRepos);
		return all;
	}

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

	/**
	 * Per-project Git repository connection. The client only ever sees repo
	 * identity + sync metadata + automation; the provider access token is stored
	 * encrypted and {@link JsonIgnore}'d so it is never serialized to a client.
	 */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Git {
		/** Stable per-connection id, so a specific repo can be re-synced / removed. */
		private String id;
		/** Provider id: {@code github} | {@code gitlab} | {@code bitbucket}. */
		private String provider;
		/** Org / group / workspace slug that owns the repository. */
		private String owner;
		/** Repository / project name. */
		private String repo;
		@Builder.Default
		private String defaultBranch = "main";
		/** User id that linked the repository. */
		private String connectedBy;
		private Instant connectedAt;
		private Instant lastSyncAt;
		/** How the repo was linked: {@code oauth} | {@code token}. */
		private String method;
		/** Suggested branch name; {@code {key}} / {@code {summary}} are filled per issue. */
		@Builder.Default
		private String branchTemplate = "{key}-{summary}";
		@Builder.Default
		private Automation automation = Automation.off();
		/** Provider access token, encrypted at rest. Never sent to clients. */
		@JsonIgnore
		private String encryptedToken;
		/** Provider webhook id, kept so the hook can be removed on disconnect. */
		private String webhookId;
		/** Webhook signing secret, encrypted at rest. Never sent to clients. */
		@JsonIgnore
		private String encryptedWebhookSecret;
	}

	/**
	 * Repo-event → workflow-transition rules, per project. Each rule stores a
	 * {@link WorkflowState#getId() state id} (never a name) so renaming/reordering
	 * the workflow never breaks a rule and a rule can never point at a state that
	 * no longer exists. All rules default to {@code off}.
	 */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Automation {
		@Builder.Default
		private Rule branchCreated = Rule.off();
		@Builder.Default
		private Rule commitPushed = Rule.off();
		@Builder.Default
		private Rule prOpened = Rule.off();
		@Builder.Default
		private Rule prMerged = Rule.off();
		/** Parse {@code #comment} / {@code #time} / transitions in commit messages. */
		@Builder.Default
		private boolean smartCommits = true;

		/** All rules off, smart commits on — the state right after connecting. */
		public static Automation off() {
			return Automation.builder().build();
		}
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Rule {
		private boolean on;
		/** Target {@link WorkflowState#getId()} in this project's workflow. */
		private String toStateId;

		public static Rule off() {
			return Rule.builder().on(false).build();
		}
	}

	/** Generates a short stable id for a workflow state / label. */
	public static String newId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	// Default workflow per the product spec (names + oklch hues).
	private static final List<String> DEFAULT_STATE_NAMES =
			List.of("Open", "In Progress", "In Parking", "In Review", "Done");
	private static final List<Integer> DEFAULT_STATE_HUES = List.of(250, 70, 255, 300, 155);
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
			if ("Backlog".equalsIgnoreCase(name.trim())) {
				return 255; // legacy default state (pre "In Parking" workflow)
			}
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
