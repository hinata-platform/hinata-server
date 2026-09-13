package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a colleague may read about somebody's entry.
 *
 * <p>The history is open to the owner, and — with {@code leadsSeeMemberEntries}
 * — to a lead. The audit metadata it reads from is a free-form map that six call
 * sites write into and a seventh will, so the question "which keys reach that
 * screen" has to be answered here, on the server, once. A client-side allow-list
 * would protect nothing: the next writer of {@code metadata.workItem} would be
 * deciding what a colleague can read without knowing it.
 */
class TimeEntryHistoryResponseTest {

	@Test
	void onlyTheKeysThisScreenIsAboutSurvive() {
		AuditLog record = AuditLog.builder()
				.id("a1")
				.action(AuditAction.TIME_ENTRY_UPDATED)
				.timestamp(Instant.parse("2026-09-07T10:00:00Z"))
				.actorId("u-lead")
				.actorLabel("Alex Lead")
				.ip("10.0.xx.xx")
				.userAgent("Hinata/10.3.3")
				.metadata(metadata())
				.build();

		TimeEntryController.HistoryEntryResponse response =
				TimeEntryController.HistoryEntryResponse.from(record, true);

		assertThat(response.metadata()).containsOnlyKeys("minutes", "date", "project", "issue");
		// `owner` and `workItem` are ids the reader already has, `commit` is a
		// smart commit's sha, and the next key nobody thought about is the reason
		// this is an allow-list rather than a deny-list.
		assertThat(response.metadata()).doesNotContainKeys("owner", "workItem", "commit");
	}

	@Test
	void theClientAddressAndTheDeviceStringNeverLeaveTheAuditScreen() {
		// They are in the record for an investigation an administrator runs. On a
		// screen every colleague with the lead policy can open, they would answer
		// a question nobody asked about where somebody works from.
		AuditLog record = AuditLog.builder()
				.id("a1")
				.action(AuditAction.TIME_ENTRY_UPDATED)
				.ip("10.0.xx.xx")
				.userAgent("Hinata/10.3.3")
				.metadata(metadata())
				.build();

		String rendered = TimeEntryController.HistoryEntryResponse.from(record, true).toString();

		assertThat(rendered).doesNotContain("10.0.").doesNotContain("Hinata/");
	}

	@Test
	void aRecordWithNoMetadataAnswersWithNoneRatherThanNull() {
		AuditLog record = AuditLog.builder().id("a1")
				.action(AuditAction.TIME_TIMER_STOPPED).build();

		assertThat(TimeEntryController.HistoryEntryResponse.from(record, true).metadata()).isEmpty();
	}

	@Test
	void theWordsOfACorrectionReachOnlyAReaderWhoIsPartOfTheConversation() {
		Map<String, String> meta = new LinkedHashMap<>();
		meta.put("date", "2026-09-07");
		meta.put("reason", "LOCK_DATE");
		meta.put("note", "Ich öffne den Tag heute noch.");
		AuditLog answer = AuditLog.builder().id("a2")
				.action(AuditAction.TIME_CORRECTION_ANSWERED).metadata(meta).build();

		assertThat(TimeEntryController.HistoryEntryResponse.from(answer, true).metadata())
				.containsEntry("note", "Ich öffne den Tag heute noch.")
				.containsEntry("reason", "LOCK_DATE");
		// A lead who may see the entry still reads "answered" and the day, not the
		// sentence an administrator wrote to somebody else about the lock date.
		assertThat(TimeEntryController.HistoryEntryResponse.from(answer, false).metadata())
				.containsOnlyKeys("date");
	}

	@Test
	void aNoteOnAnyOtherRecordStaysHiddenWhoeverReads() {
		Map<String, String> meta = new LinkedHashMap<>();
		meta.put("minutes", "45");
		meta.put("note", "written somewhere it was never meant to be read");
		AuditLog edit = AuditLog.builder().id("a3")
				.action(AuditAction.TIME_ENTRY_UPDATED).metadata(meta).build();

		assertThat(TimeEntryController.HistoryEntryResponse.from(edit, true).metadata())
				.containsOnlyKeys("minutes");
	}

	private static Map<String, String> metadata() {
		Map<String, String> meta = new LinkedHashMap<>();
		meta.put("workItem", "w-1");
		meta.put("owner", "u-owner");
		meta.put("minutes", "45");
		meta.put("date", "2026-09-07");
		meta.put("project", "p-1");
		meta.put("issue", "HIN-1");
		meta.put("commit", "abc1234");
		return meta;
	}
}
