package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.TimePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose approval conversation a lead may read in an entry's history: the one about a project
 * they lead, judged by the project the record names, not by where the entry sits now. An
 * entry that moved carries the conversations of its old project with it.
 */
class EntryHistoryConversationTest {

	private static AuditLog approvalRecord(String projectId) {
		return AuditLog.builder().action(AuditAction.TIME_CORRECTION_REQUESTED)
				.metadata(Map.of("project", projectId,
						"reason", TimePolicy.LockReason.APPROVAL.name(), "note", "please reopen"))
				.build();
	}

	/** A lead's view of the history of an entry that now sits in [entryProject]. */
	private static TimeTrackingService.EntryHistory readBy(Set<String> ledProjects, String entryProject) {
		return new TimeTrackingService.EntryHistory(Page.empty(), false, ledProjects, entryProject, false,
				Set.of());
	}

	private static TimeTrackingService.EntryHistory readBy(Set<String> ledProjects) {
		return readBy(ledProjects, "new");
	}

	@Test
	void theLeadOfTheProjectTheRecordIsAboutReadsIt() {
		assertThat(readBy(Set.of("old")).readsConversation(approvalRecord("old"))).isTrue();
	}

	@Test
	void theLeadOfTheProjectTheEntryMovedToDoesNot() {
		assertThat(readBy(Set.of("new")).readsConversation(approvalRecord("old"))).isFalse();
	}

	@Test
	void aRecordThatNamesNoProjectIsReadAgainstTheEntrysProject() {
		// Records from before they named their project (an old backfill grant) do not vanish
		// for the lead who could read them until then.
		AuditLog unnamed = AuditLog.builder().action(AuditAction.TIME_BACKFILL_GRANTED)
				.metadata(Map.of("reason", TimePolicy.LockReason.APPROVAL.name())).build();

		assertThat(readBy(Set.of("old"), "old").readsConversation(unnamed)).isTrue();
		assertThat(readBy(Set.of("other"), "old").readsConversation(unnamed)).isFalse();
		assertThat(readBy(Set.of("old"), null).readsConversation(unnamed)).isFalse();
	}

	@Test
	void theOwnerAndAnAdministratorReadEveryConversation() {
		TimeTrackingService.EntryHistory owner =
				new TimeTrackingService.EntryHistory(Page.empty(), true, Set.of(), "old", false, Set.of());

		assertThat(owner.readsConversation(approvalRecord("old"))).isTrue();
	}
}
