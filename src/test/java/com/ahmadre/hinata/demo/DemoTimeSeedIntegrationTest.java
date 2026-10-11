package com.ahmadre.hinata.demo;

import com.ahmadre.hinata.billing.BillingRate;
import com.ahmadre.hinata.billing.Invoice;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.ics.IcsUrlCipher;
import com.ahmadre.hinata.timetracking.CalendarEvent;
import com.ahmadre.hinata.timetracking.CalendarSubscription;
import com.ahmadre.hinata.timetracking.RunningTimer;
import com.ahmadre.hinata.timetracking.TimeEntryShare;
import com.ahmadre.hinata.timetracking.TimeSavedReport;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo workspace with every part of the extended module switched on (HIN-98).
 *
 * <p>The seeder writes the module's collections as raw documents, because it may not call the
 * module. That leaves one thing nobody checks by looking at the code: whether the module can read
 * back what was written. Each test here reads through the module's own document classes, so a
 * field the seeder spells differently fails here instead of in a screenshot.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true",
		"hinata.time-tracking.billing-enabled=true",
		"hinata.time-tracking.absence-management-enabled=true",
		"hinata.time-tracking.ics-import-enabled=true",
		"hinata.ics.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Testcontainers(disabledWithoutDocker = true)
class DemoTimeSeedIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private DemoSeeder seeder;
	@Autowired
	private IcsUrlCipher cipher;

	private String adminId() {
		return users.findByUsernameIgnoreCase("admin").orElseThrow().getId();
	}

	@Test
	@DisplayName("the admin has a timer running, on an issue")
	void aTimerRuns() {
		List<RunningTimer> timers = mongo.findAll(RunningTimer.class);

		assertThat(timers).singleElement().satisfies(timer -> {
			assertThat(timer.getUserId()).isEqualTo(adminId());
			assertThat(timer.getMode()).isEqualTo(RunningTimer.Mode.STOPWATCH);
			assertThat(timer.getIssueId()).isNotNull();
			assertThat(timer.getStartedAt()).isNotNull();
		});
	}

	@Test
	@DisplayName("entries with a start and an end cover last week as well as this one")
	void twoWeeksOfTimedEntries() {
		LocalDate lastMonday = LocalDate.now(ZoneOffset.UTC).with(DayOfWeek.MONDAY).minusWeeks(1);
		List<WorkItem> lastWeek = mongo.findAll(WorkItem.class).stream()
				.filter(item -> item.getStartedAt() != null)
				.filter(item -> !item.getDate().isBefore(lastMonday)
						&& item.getDate().isBefore(lastMonday.plusDays(7)))
				.toList();

		assertThat(lastWeek).hasSizeGreaterThanOrEqualTo(10);
		assertThat(lastWeek).allSatisfy(item -> assertThat(item.getEndedAt()).isAfter(item.getStartedAt()));
		assertThat(lastWeek).anySatisfy(item -> assertThat(item.isBillable()).isTrue());
	}

	@Test
	@DisplayName("a saved report, a waiting share and a calendar with events")
	void theSmallerFeaturesHaveARow() {
		assertThat(mongo.findAll(TimeSavedReport.class)).singleElement().satisfies(report -> {
			assertThat(report.getOwnerId()).isEqualTo(adminId());
			assertThat(report.getConfig().getRange()).isEqualTo(TimeSavedReport.Range.THIS_WEEK);
		});
		assertThat(mongo.findAll(TimeEntryShare.class)).singleElement().satisfies(share -> {
			assertThat(share.getStatus()).isEqualTo(TimeEntryShare.Status.PENDING);
			assertThat(share.getEntry().getDurationMinutes()).isPositive();
		});
		CalendarSubscription subscription = mongo.findAll(CalendarSubscription.class).getFirst();
		assertThat(subscription.isEnabled()).isTrue();
		// Bound to the record like the module binds it, or the first read could not open it.
		assertThat(cipher.decrypt(subscription.getEncryptedUrl(), "calendar-subscription:" + subscription.getId()))
				.isEqualTo("https://calendar.example.com/team.ics");
		assertThat(mongo.findAll(CalendarEvent.class)).hasSize(8)
				.allSatisfy(event -> assertThat(event.getSubscriptionId()).isEqualTo(subscription.getId()));
	}

	@Test
	@DisplayName("billing has rates and one draft that locks nothing")
	void billingHasADraft() {
		assertThat(mongo.findAll(BillingRate.class)).hasSize(3);
		Invoice draft = mongo.findAll(Invoice.class).getFirst();

		assertThat(draft.getStatus()).isEqualTo(Invoice.Status.DRAFT);
		assertThat(draft.getNumber()).isNull();
		assertThat(draft.getTotals().grossCents()).isGreaterThan(draft.getTotals().netCents());
		List<String> entryIds = draft.getLines().getFirst().entryIds();
		assertThat(entryIds).isNotEmpty();
		assertThat(mongo.findAll(WorkItem.class)).filteredOn(item -> entryIds.contains(item.getId()))
				.hasSize(entryIds.size())
				.allSatisfy(item -> assertThat(item.getInvoiceId()).isNull());
	}

	@Test
	@DisplayName("seeding again on reset rebuilds the same workspace, and nothing twice")
	void aResetRebuildsEverything() {
		Map<String, Long> before = counts();

		seeder.seed();

		assertThat(counts()).isEqualTo(before);
		assertThat(mongo.findAll(RunningTimer.class)).hasSize(1);
	}

	private Map<String, Long> counts() {
		List<String> names = new java.util.ArrayList<>(DemoSeeder.MODULE_COLLECTIONS);
		names.addAll(List.of("work_items", "running_timers", "timesheet_approvals", "time_off_types"));
		names.remove("notifications");
		return names.stream().collect(Collectors.toMap(Function.identity(),
				name -> mongo.getCollection(name).countDocuments()));
	}
}
