package com.ahmadre.hinata.project;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Partial update for a project's settings surface. Every field is optional; a
 * {@code null} field is left untouched. Structural fields (key, leads, members,
 * workflow, labels, archive) are validated and cascaded atomically in
 * {@link ProjectService#applyUpdate}.
 */
public record ProjectUpdateRequest(
		@Size(min = 2, max = 10) String key,
		@Size(max = 120) String name,
		@Size(max = 4000) String description,
		/** Legacy single primary lead; superseded by {@link #leadIds()}. */
		String leadId,
		List<String> leadIds,
		List<String> memberIds,
		List<Project.WorkflowState> workflowStates,
		List<String> resolvedStates,
		List<Project.Label> labels,
		String color,
		Boolean archived,
		/** Maps a to-be-deleted workflow-state id to the surviving state id its
		 * issues should be migrated into. Required when deleting a state that
		 * still has issues assigned. */
		Map<String, String> stateMigrations) {
}
