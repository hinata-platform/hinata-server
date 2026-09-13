package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.timetracking.WorkItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A token reads no more of a colleague's entry than the person holding it could in
 * the app: the hours, the day and the activity, and nothing about who worked or what
 * they wrote.
 */
class WorkItemViewTest {

	private static WorkItem entry() {
		return WorkItem.builder().id("w1").issueId("i1").projectId("p1").userId("u1")
				.date(LocalDate.of(2026, 9, 9)).durationMinutes(45).activityType("Meeting")
				.description("Arzttermin nachgeholt").billable(true)
				.createdAt(Instant.parse("2026-09-09T20:00:00Z"))
				.startedAt(Instant.parse("2026-09-09T08:00:00Z"))
				.endedAt(Instant.parse("2026-09-09T08:45:00Z"))
				.tags(List.of("privat")).source(WorkItem.Source.TIMER)
				.updatedAt(Instant.parse("2026-09-10T07:00:00Z")).updatedBy("u2")
				.sharedFromId("w0").build();
	}

	@Test
	void anEntryWhoseDetailsTheCallerMayNotReadCarriesOnlyTheHours() {
		TimeTrackingTools.WorkItemView view = TimeTrackingTools.WorkItemView.of(entry(), false);

		assertThat(view.hidden()).isTrue();
		assertThat(view.durationMinutes()).isEqualTo(45);
		assertThat(view.date()).isEqualTo(LocalDate.of(2026, 9, 9));
		assertThat(view.activityType()).isEqualTo("Meeting");
		assertThat(view.userId()).isNull();
		assertThat(view.description()).isNull();
		assertThat(view.createdAt()).isNull();
		assertThat(view.startedAt()).isNull();
		assertThat(view.endedAt()).isNull();
		assertThat(view.tags()).isEmpty();
		assertThat(view.updatedAt()).isNull();
		assertThat(view.updatedBy()).isNull();
		assertThat(view.sharedFromId()).isNull();
	}

	@Test
	void anEntryTheCallerMayReadIsComplete() {
		TimeTrackingTools.WorkItemView view = TimeTrackingTools.WorkItemView.of(entry(), true);

		assertThat(view.hidden()).isFalse();
		assertThat(view.userId()).isEqualTo("u1");
		assertThat(view.description()).isEqualTo("Arzttermin nachgeholt");
		assertThat(view.tags()).containsExactly("privat");
		assertThat(view.source()).isEqualTo("TIMER");
	}
}
