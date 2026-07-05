package com.ahmadre.hinata.mcp;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * MCP prompt templates that steer the model through common hinata workflows.
 * They are static instruction text parameterised by an {@link McpArg} — they do
 * not touch any service or data themselves; the model is expected to gather what
 * it needs through the read tools / resources (which enforce the caller's ACLs).
 * A String return is wrapped by the framework into a single-message prompt.
 */
@Component
public class HinataPrompts {

	@McpPrompt(name = "triage_issue", title = "Triage an issue",
			description = "Guide the model to review an issue and recommend a triage.")
	public String triageIssue(
			@McpArg(name = "issueRef", description = "Issue id or readable id, e.g. ASTA-42", required = true)
			String issueRef) {
		String ref = issueRef == null || issueRef.isBlank() ? "the issue" : issueRef.trim();
		return """
				You are triaging issue %s in hinata.

				1. Call the `get_issue` tool with "%s" to load the issue, then \
				`get_issue_hierarchy` to understand its parent epic and any sub-tasks.
				2. Summarise the issue in two or three sentences: what is being asked, \
				and the current state.
				3. Recommend a triage:
				   - a priority (LOW, NORMAL, HIGH, URGENT) with a one-line justification;
				   - a short list of labels that would help categorise it;
				   - whether it looks like a duplicate or is missing information needed \
				to act on it.
				Be concise and base every claim strictly on the issue's own content — do \
				not invent details.""".formatted(ref, ref);
	}

	@McpPrompt(name = "sprint_standup", title = "Sprint stand-up summary",
			description = "Guide the model to summarise the caller's in-progress work for a stand-up.")
	public String sprintStandup(
			@McpArg(name = "projectKey", description = "Project key, e.g. ASTA", required = true)
			String projectKey) {
		String key = projectKey == null || projectKey.isBlank() ? "the project" : projectKey.trim();
		return """
				Prepare a stand-up summary for the current user in project %s.

				1. Call `list_my_issues` to fetch the issues assigned to the caller. \
				If you need project context, call `get_project` with "%s".
				2. Group the caller's issues by workflow state (e.g. In Progress, In \
				Review, Blocked / waiting, Done recently).
				3. Produce a short stand-up in three parts:
				   - "Yesterday / recently done": resolved or advanced items;
				   - "Today / in progress": what is actively being worked on;
				   - "Blockers": anything stalled, waiting or overdue.
				Keep it to a few bullet points per part, reference issues by their \
				readable id, and only mention issues actually returned by the tools.""".formatted(key, key);
	}
}
