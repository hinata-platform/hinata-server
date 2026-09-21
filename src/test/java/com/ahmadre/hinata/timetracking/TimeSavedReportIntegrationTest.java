package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saved reports against a real database (HIN-93): a shared link opens in the reader's own scope
 * and dies when taken back; a schedule mails each recipient once per period with what that
 * recipient may see; a deleted account leaves no report and no recipient entry behind.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Import(TestClock.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeSavedReportIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	/** A Monday; the week before it is the one the reports cover. */
	private static final Instant MONDAY_MORNING = Instant.parse("2026-09-07T08:10:00Z");
	private static final LocalDate LAST_WEEK = LocalDate.of(2026, 9, 1);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeSavedReportService saved;
	@Autowired
	private TimeReportService reports;
	@Autowired
	private TimeReportMails mails;
	@Autowired
	private UserRepository users;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private SettingsService settings;
	@Autowired
	private ApplicationEventPublisher events;
	@Autowired
	private TestClock clock;

	private User lead;
	private User member;
	private User stranger;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, WorkItem.class, TimeSavedReport.class,
				Notification.class, TimeMark.class)) {
			mongo.remove(new Query(), type);
		}
		settings.save(new ServerSettings());
		clock.set(MONDAY_MORNING);
		lead = person("lead");
		member = person("member");
		stranger = person("stranger");
		Project project = mongo.insert(Project.builder().key("APO").name("Apollo").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId()))).build());
		mongo.insert(WorkItem.builder().userId(lead.getId()).projectId(project.getId()).date(LAST_WEEK)
				.durationMinutes(60).build());
		mongo.insert(WorkItem.builder().userId(member.getId()).projectId(project.getId()).date(LAST_WEEK)
				.durationMinutes(30).build());
	}

	@Test
	void aSharedLinkOpensInTheReadersScopeAndDiesWhenTakenBack() {
		TimeSavedReportService.View report = saved.create(lead, byPerson("Last week by person"));
		String token = saved.share(lead, report.id()).token();

		TimeSavedReportService.View opened = saved.opened(stranger, token);
		assertThat(opened.owned()).isFalse();
		assertThat(opened.schedule()).isNull();
		// The stranger runs the owner's question and sees nothing of anybody.
		assertThat(run(stranger, opened).totals().minutes()).isZero();
		assertThat(run(member, saved.opened(member, token)).totals().minutes()).isEqualTo(30);

		String second = saved.share(lead, report.id()).token();
		assertThatThrownBy(() -> saved.opened(stranger, token)).isInstanceOf(ApiException.class);
		saved.unshare(lead, report.id());
		assertThatThrownBy(() -> saved.opened(stranger, second)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> saved.update(member, report.id(), new TimeSavedReportService.Draft("mine", null)))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void aScheduleMailsEachRecipientOncePerPeriodWithWhatTheyMaySee() {
		clock.set(Instant.parse("2026-09-07T06:00:00Z"));
		TimeSavedReportService.View report = saved.create(lead, byPerson("Weekly"));
		saved.schedule(lead, report.id(), new TimeSavedReportService.ScheduleDraft(TimeSavedReport.Cadence.WEEKLY,
				DayOfWeek.MONDAY, 7, List.of(member.getId(), lead.getId())));

		clock.set(Instant.parse("2026-09-07T06:30:00Z"));
		mails.run();
		assertThat(sent()).isEmpty();

		clock.set(MONDAY_MORNING);
		mails.run();
		mails.run();
		assertThat(sent()).hasSize(2);
		assertThat(sentTo(member).getBody()).contains("0:30");
		assertThat(sentTo(lead).getBody()).contains("1:00");

		clock.set(MONDAY_MORNING.plusSeconds(7 * 86_400));
		mails.run();
		assertThat(sent()).hasSize(4);
		// The mail's link opens the report for a recipient, and for nobody else.
		assertThat(saved.openedById(member, report.id()).owned()).isFalse();
		assertThatThrownBy(() -> saved.openedById(stranger, report.id())).isInstanceOf(ApiException.class);
	}

	@Test
	void aDeletedAccountLeavesNoReportAndNoRecipientEntry() {
		TimeSavedReportService.View theirs = saved.create(member, byPerson("Mine"));
		TimeSavedReportService.View leads = saved.create(lead, byPerson("Team"));
		saved.schedule(lead, leads.id(), new TimeSavedReportService.ScheduleDraft(TimeSavedReport.Cadence.MONTHLY,
				null, 7, List.of(member.getId(), lead.getId())));

		events.publishEvent(new UserService.UserDeletedEvent(member.getId()));

		assertThat(mongo.findById(theirs.id(), TimeSavedReport.class)).isNull();
		assertThat(mongo.findById(leads.id(), TimeSavedReport.class).getSchedule().getRecipients())
				.containsExactly(lead.getId());
	}

	// --- fixtures -----------------------------------------------------------

	private TimeSavedReportService.Draft byPerson(String name) {
		return new TimeSavedReportService.Draft(name, TimeSavedReport.Config.builder()
				.range(TimeSavedReport.Range.LAST_WEEK).groupBy(TimeReportService.GroupBy.USER).build());
	}

	private TimeReportService.Summary run(User reader, TimeSavedReportService.View view) {
		TimeReportFilter filter = reports.filter(reader, TimeSavedReportService.query(view.config(), view.from(),
				view.to()));
		return reports.summary(reader, filter, view.config().getGroupBy(), 0, 50);
	}

	private List<Notification> sent() {
		return notifications.findAll().stream()
				.filter(notification -> notification.getType() == Notification.Type.TIME_REPORT_SCHEDULED).toList();
	}

	private Notification sentTo(User user) {
		return sent().stream().filter(notification -> notification.getUserId().equals(user.getId())).findFirst()
				.orElseThrow();
	}

	private User person(String name) {
		return users.save(User.builder().username(name).displayName(name).email(name + "@example.test")
				.roles(Set.of(Role.MEMBER)).active(true).locale("en").timezone("UTC").build());
	}
}
