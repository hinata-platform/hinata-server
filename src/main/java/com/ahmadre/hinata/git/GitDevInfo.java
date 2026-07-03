package com.ahmadre.hinata.git;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Development information for a single issue — the branches, commits,
 * pull/merge requests and builds that reference the issue key. Modelled on
 * Atlassian's "development information" (DevInfo): a branch/commit/PR is linked
 * to an issue when the issue key appears in its name/message/title.
 *
 * <p>In a fully wired deployment this document is <em>derived server-side</em>
 * from provider webhook events (push, branch, PR, workflow_run…). Until real
 * provider credentials + a public webhook URL are configured it is populated by
 * the {@code DemoSeeder}; the read path, shape and client behaviour are
 * identical either way, so the app never carries fixture data itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("git_dev_info")
public class GitDevInfo {

	@Id
	private String id;

	/** Issue readable id, e.g. {@code HIN-42} — the linking key (case-insensitive). */
	@Indexed(unique = true)
	private String issueKey;

	@Indexed
	private String projectId;

	@Builder.Default
	private List<Branch> branches = new ArrayList<>();
	@Builder.Default
	private List<Commit> commits = new ArrayList<>();
	@Builder.Default
	private List<PullRequest> prs = new ArrayList<>();
	@Builder.Default
	private List<Build> builds = new ArrayList<>();

	private Instant updatedAt;

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Branch {
		private String name;
		private String base;
		private int ahead;
		private int behind;
		private Instant updatedAt;
		private String authorId;
		/** Source repo attribution (multi-repo): provider id + {@code owner/repo}. */
		private String provider;
		private String repo;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Commit {
		private String sha;
		private String message;
		private String authorId;
		private Instant at;
		private int additions;
		private int deletions;
		private boolean verified;
		private String provider;
		private String repo;
	}

	/** state: {@code OPEN} | {@code DRAFT} | {@code MERGED} | {@code CLOSED}. */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PullRequest {
		private int number;
		private String title;
		private String state;
		private String authorId;
		@Builder.Default
		private List<String> reviewerIds = new ArrayList<>();
		private int approvals;
		private int changesRequested;
		private int comments;
		private String sourceBranch;
		private String targetBranch;
		private Instant at;
		/** checks roll-up: {@code passing} | {@code failing} | {@code pending} | {@code running}. */
		private String checks;
		private String provider;
		private String repo;
	}

	/** status: {@code passing} | {@code failing} | {@code pending} | {@code running}. */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Build {
		private String name;
		private String workflow;
		private String branch;
		private String status;
		private String duration;
		private Instant at;
		private String provider;
		private String repo;
	}
}
