package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The three HIN-92 notifications: switchable through {@code time}, and quiet on a lock screen.
 *
 * <p>The same two silent failures as the timesheet notices: a type missing from {@code eventId}
 * falls to {@code security} and can never be switched off, and a push naming hours, a project or
 * an issue puts working time on a lock screen (R7).
 */
class TimeReminderNotificationTest {

	private UserRepository users;
	private NotificationRepository notifications;
	private MailService mail;
	private PushService push;
	private NotificationService service;

	private final User person = person("u-person");
	private final User lead = person("u-lead");

	private static User person(String id) {
		return User.builder().id(id).username(id).displayName(id).email(id + "@example.test")
				.active(true).locale("de").build();
	}

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		notifications = mock(NotificationRepository.class);
		mail = mock(MailService.class);
		push = mock(PushService.class);
		lenient().when(users.findById(anyString())).thenReturn(Optional.empty());
		lenient().when(users.findById(person.getId())).thenReturn(Optional.of(person));
		lenient().when(users.findById(lead.getId())).thenReturn(Optional.of(lead));
		service = new NotificationService(notifications, users, mail, push,
				mock(GatewayService.class), new RichTextService(), mock(ProjectReach.class),
				new IssueChangeRenderer(users, mock(SprintRepository.class),
						mock(IssueRepository.class), mock(ProjectRepository.class),
						com.ahmadre.hinata.common.UserWordsFixture.real()),
				com.ahmadre.hinata.common.UserWordsFixture.real(),
				mock(IssueDigestService.class));
	}

	private void timeEvent(boolean email, boolean pushOn) {
		for (User someone : Set.of(person, lead)) {
			NotificationPreferences prefs = NotificationPreferences.defaults();
			prefs.getEvents().put("time", new NotificationPreferences.Channel(email, pushOn));
			someone.setNotificationPreferences(prefs);
		}
	}

	private void fireAllThree() {
		service.notifyTimeTargetReminder(person, NotificationService.TargetPeriod.DAY, 300, 480);
		service.notifyTimeBudgetAlert(Set.of(lead.getId()), "p-1", "Apollo", NotificationService.TimeLimit.BUDGET, 80, 480, 600);
		service.notifyTimeEstimateReached(Set.of(person.getId()), "APO-7", "p-1", 100, 90, 60);
	}

	@Test
	void everyOneOfThemIsSwitchedOffByTheTimeEvent() {
		timeEvent(false, false);

		fireAllThree();

		verify(notifications, times(3)).save(any());
		verify(mail, never()).sendNotification(anyString(), anyString(), anyString(), anyString(),
				any(), any(), any(), any());
		verify(mail, never()).sendNotification(anyString(), anyString(), anyString(), anyString(),
				any(), any(), any(), any(), any());
		assertThat(mockingDetails(push).getInvocations()).isEmpty();
	}

	@Test
	void thePushNamesNeitherHoursNorProjectNorIssue() {
		timeEvent(false, true);

		fireAllThree();

		List<Invocation> pushes = List.copyOf(mockingDetails(push).getInvocations());
		assertThat(pushes).hasSize(3);
		for (Invocation sent : pushes) {
			String title = sent.getArgument(1);
			String body = sent.getArgument(2);
			assertThat(title + " " + body).doesNotContain("Apollo", "APO-7", "%").doesNotContainPattern("\\d");
		}
	}

	@Test
	void theBellSaysHowMuchIsMissingInThePersonsLanguage() {
		timeEvent(false, false);

		service.notifyTimeTargetReminder(person, NotificationService.TargetPeriod.DAY, 300, 480);
		service.notifyTimeTargetReminder(person, NotificationService.TargetPeriod.WEEK, 0, 2400);

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications, times(2)).save(saved.capture());
		assertThat(saved.getAllValues().get(0).getBody())
				.isEqualTo("Dir fehlen heute noch 3 h bis zu deinem Tagesziel von 8 h.");
		assertThat(saved.getAllValues().get(0).getLink()).isEqualTo("/time");
		assertThat(saved.getAllValues().get(1).getBody())
				.isEqualTo("Du hast diese Woche noch keine Zeit erfasst. Dein Wochenziel sind 40 h.");
	}

	@Test
	void onlyTheLatestReminderStaysInTheBellAndItsPushLooksLikeAnyTimeNotice() {
		timeEvent(false, true);

		service.notifyTimeTargetReminder(person, NotificationService.TargetPeriod.DAY, 300, 480);

		// Kept, the reminders would add up to a history of the days somebody fell short.
		verify(notifications).deleteByUserIdAndType(person.getId(), Notification.Type.TIME_TARGET_REMINDER);
		Invocation sent = mockingDetails(push).getInvocations().iterator().next();
		assertThat((String) sent.getArgument(1)).isEqualTo("Zeiterfassung");
		java.util.Map<String, String> data = sent.getArgument(4);
		assertThat(data).containsEntry("type", "TIME");
	}

	@Test
	void anAlertNamesTheProjectAndTheSumsAndNobodyWhoRecordedThem() {
		timeEvent(false, false);

		service.notifyTimeBudgetAlert(Set.of(lead.getId()), "p-1", "Apollo", NotificationService.TimeLimit.ESTIMATES, 100, 630, 600);

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications).save(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo(lead.getId());
		assertThat(saved.getValue().getType()).isEqualTo(Notification.Type.TIME_BUDGET_ALERT);
		assertThat(saved.getValue().getBody())
				.isEqualTo("Apollo hat 100 % der geschätzten Zeit erreicht (10,5 h von 10 h).");
	}
}
