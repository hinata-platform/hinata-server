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

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The five timesheet notifications: switchable, and quiet on a lock screen.
 *
 * <p>Two failures this is written against, and both are silent. A new
 * {@code Notification.Type} that nobody adds to {@code eventId} falls through to
 * the {@code default} branch and lands on {@code security} — which is the one
 * event that can never be switched off, so the person would be mailed and pushed
 * about every timesheet for ever with a preference screen that says otherwise.
 * And a push body that named the hours or the project would put somebody's
 * working time on a lock screen in front of whoever is standing there (R7).
 */
class TimesheetNotificationTest {

	private UserRepository users;
	private NotificationRepository notifications;
	private MailService mail;
	private PushService push;
	private NotificationService service;

	private final User owner = person("u-owner");
	private final User approver = person("u-approver");

	private static User person(String id) {
		return User.builder().id(id).username(id).displayName(id).email(id + "@example.test")
				.active(true).locale("en").build();
	}

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		notifications = mock(NotificationRepository.class);
		mail = mock(MailService.class);
		push = mock(PushService.class);
		lenient().when(users.findById(anyString())).thenReturn(Optional.empty());
		lenient().when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
		lenient().when(users.findById(approver.getId())).thenReturn(Optional.of(approver));
		RichTextService richText = new RichTextService();
		service = new NotificationService(notifications, users, mail, push,
				mock(GatewayService.class), richText, mock(ProjectReach.class),
				new IssueChangeRenderer(users, mock(SprintRepository.class),
						mock(IssueRepository.class), mock(ProjectRepository.class),
						com.ahmadre.hinata.common.UserWordsFixture.real()),
				com.ahmadre.hinata.common.UserWordsFixture.real(),
				mock(IssueDigestService.class));
	}

	/** Silences the {@code time} event for both people, leaving everything else on. */
	private void silenceTimeEvent() {
		for (User person : Set.of(owner, approver)) {
			NotificationPreferences prefs = NotificationPreferences.defaults();
			prefs.getEvents().put("time", new NotificationPreferences.Channel(false, false));
			person.setNotificationPreferences(prefs);
		}
	}

	/** Turns both channels on for the {@code time} event, so delivery is observable. */
	private void enableTimeEvent() {
		for (User person : Set.of(owner, approver)) {
			NotificationPreferences prefs = NotificationPreferences.defaults();
			prefs.getEvents().put("time", new NotificationPreferences.Channel(true, true));
			person.setNotificationPreferences(prefs);
		}
	}

	private void fireAllFive() {
		service.notifyTimesheetSubmitted(Set.of(approver.getId()), "Lena", "/time/approvals");
		for (NotificationService.TimesheetEvent event : NotificationService.TimesheetEvent.values()) {
			service.notifyTimesheetDecided(owner, event, "2026-08-01 – 2026-08-31",
					"/time/timesheet");
		}
		service.notifyTimeCorrectionRequested(Set.of(approver.getId()), "Lena", "/time/approvals");
	}

	@Test
	void everyOneOfThemIsSwitchedOffByTheTimeEvent() {
		silenceTimeEvent();

		fireAllFive();

		// The bell is always recorded — that is the contract for every type. What
		// the preference governs is mail and push, and a type that had fallen
		// through to `security` would be delivered on both regardless.
		verify(notifications, org.mockito.Mockito.times(5)).save(any());
		verify(mail, never()).sendNotification(anyString(), anyString(), anyString(), anyString(),
				any(), any(), any(), any());
		verify(push, never()).sendToUser(anyString(), anyString(), anyString(), any(), anyMap());
		verify(push, never()).sendToUser(anyString(), anyString(), anyString(), any());
	}

	@Test
	void andIsDeliveredWhenTheEventIsOnSoTheTestAboveIsNotVacuous() {
		enableTimeEvent();

		fireAllFive();

		verify(push, org.mockito.Mockito.times(5))
				.sendToUser(anyString(), anyString(), anyString(), any(), anyMap());
	}

	@Test
	void thePushBodyNamesNeitherHoursNorProjectNorTheSpan() {
		enableTimeEvent();

		fireAllFive();

		ArgumentCaptor<String> pushBodies = ArgumentCaptor.forClass(String.class);
		verify(push, org.mockito.Mockito.times(5)).sendToUser(anyString(), anyString(),
				pushBodies.capture(), any(), anyMap());
		assertThat(pushBodies.getAllValues()).allSatisfy(body -> assertThat(body)
				.doesNotContain("2026-08-01")
				.doesNotContainIgnoringCase("hour")
				.doesNotContainIgnoringCase("week"));
	}

	@Test
	void theInAppBodyOfADecisionDoesNameTheSpanBecauseThatIsWhereDetailBelongs() {
		enableTimeEvent();

		service.notifyTimesheetDecided(owner, NotificationService.TimesheetEvent.APPROVED,
				"2026-08-01 – 2026-08-31", "/time/timesheet");

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications).save(saved.capture());
		assertThat(saved.getValue().getBody()).contains("2026-08-01 – 2026-08-31");
		// And never the word "week": how often timesheets are handed in is the
		// operator's decision, so no sentence may assume one.
		assertThat(saved.getValue().getBody()).doesNotContainIgnoringCase("week");
	}

	@Test
	void thePushCarriesItsTypeSoAClientNeedNotGuessFromTheRoute() {
		enableTimeEvent();

		service.notifyTimesheetSubmitted(Set.of(approver.getId()), "Lena", "/time/approvals");

		verify(push).sendToUser(eq(approver.getId()), anyString(), anyString(),
				eq("/time/approvals"),
				eq(java.util.Map.of("type", Notification.Type.TIMESHEET_SUBMITTED.name())));
	}

	private static <T> T eq(T value) {
		return org.mockito.ArgumentMatchers.eq(value);
	}
}
