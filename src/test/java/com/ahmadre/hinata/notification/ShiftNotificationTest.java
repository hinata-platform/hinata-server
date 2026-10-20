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
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notification side of HIN-46: every {@code SHIFT_*} type is switched by {@code shifts} and
 * never locked, the fan-out drops whoever cannot see the project, the push gets its own words, and
 * every type has a mail eyebrow in every language.
 */
class ShiftNotificationTest {

	private static final Locale[] SUPPORTED = {Locale.ENGLISH, Locale.GERMAN, Locale.CHINESE,
			Locale.forLanguageTag("hi"), Locale.forLanguageTag("es"), Locale.JAPANESE,
			Locale.FRENCH, Locale.forLanguageTag("ru"), Locale.forLanguageTag("ar")};

	private UserRepository users;
	private NotificationRepository notifications;
	private MailService mail;
	private PushService push;
	private ProjectReach reach;
	private NotificationService service;

	private final User ann = person("u-ann");
	private final User bob = person("u-bob");

	private static User person(String id) {
		return User.builder().id(id).username(id).displayName(id).email(id + "@example.test")
				.active(true).locale("de").build();
	}

	private static List<Notification.Type> shiftTypes() {
		return Arrays.stream(Notification.Type.values()).filter(type -> type.name().startsWith("SHIFT_")).toList();
	}

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		notifications = mock(NotificationRepository.class);
		mail = mock(MailService.class);
		push = mock(PushService.class);
		reach = mock(ProjectReach.class);
		Map<String, User> byId = new HashMap<>(Map.of(ann.getId(), ann, bob.getId(), bob));
		lenient().when(users.findById(anyString())).thenAnswer(call -> Optional.ofNullable(byId.get(call.<String>getArgument(0))));
		lenient().when(users.findAllById(org.mockito.ArgumentMatchers.<Iterable<String>>any())).thenAnswer(call -> {
			List<User> found = new ArrayList<>();
			for (String id : call.<Iterable<String>>getArgument(0)) {
				if (byId.containsKey(id)) found.add(byId.get(id));
			}
			return found;
		});
		lenient().when(users.findNoticeReadersByIdIn(any())).thenAnswer(call -> {
			List<UserRepository.NoticeReaderView> found = new ArrayList<>();
			for (String id : call.<java.util.Collection<String>>getArgument(0)) {
				if (byId.containsKey(id)) found.add(reader(byId.get(id)));
			}
			return found;
		});
		service = new NotificationService(notifications, users, mail, push,
				mock(GatewayService.class), new RichTextService(), reach,
				new com.ahmadre.hinata.issue.IssueReach(mock(ProjectReach.class)),
				new IssueChangeRenderer(users, mock(SprintRepository.class),
						mock(IssueRepository.class), mock(ProjectRepository.class),
						com.ahmadre.hinata.common.UserWordsFixture.real()),
				com.ahmadre.hinata.common.UserWordsFixture.real(),
				NotificationDaysFixture.weekday(), mock(IssueDigestService.class));
	}

	private void shifts(boolean email, boolean pushOn) {
		for (User someone : List.of(ann, bob)) {
			NotificationPreferences prefs = NotificationPreferences.defaults();
			prefs.getEvents().put(NotificationPreferences.SHIFTS, new NotificationPreferences.Channel(email, pushOn));
			someone.setNotificationPreferences(prefs);
		}
	}

	/** The projection the service reads, backed by a whole account. */
	private static UserRepository.NoticeReaderView reader(User user) {
		return new UserRepository.NoticeReaderView() {
			public String getId() { return user.getId(); }
			public String getUsername() { return user.getUsername(); }
			public String getDisplayName() { return user.getDisplayName(); }
			public String getEmail() { return user.getEmail(); }
			public String getLocale() { return user.getLocale(); }
			public String getTimezone() { return user.getTimezone(); }
			public Boolean getActive() { return user.isActive(); }
			public NotificationPreferences getNotificationPreferences() { return user.getNotificationPreferences(); }
		};
	}

	@SuppressWarnings("unchecked")
	private List<Notification> savedBells(int calls) {
		ArgumentCaptor<Iterable<Notification>> saved = ArgumentCaptor.forClass(Iterable.class);
		verify(notifications, org.mockito.Mockito.times(calls)).saveAll(saved.capture());
		List<Notification> out = new ArrayList<>();
		saved.getAllValues().forEach(batch -> batch.forEach(out::add));
		return out;
	}

	private static PersonalNotice notice() {
		return new PersonalNotice("Neue Schicht", "Aufbau bei Sommerfest, 21.10.2026, 14:00 bis 18:00",
				"Schichten", "Du hast eine neue Schicht.", "Schichten öffnen");
	}

	@Test
	void everyShiftTypeIsSwitchedByShiftsAndNoneIsLocked() {
		assertThat(shiftTypes()).isNotEmpty();
		for (Notification.Type type : shiftTypes()) {
			assertThat(NotificationService.eventId(type)).as(type.name()).isEqualTo(NotificationPreferences.SHIFTS);
		}
		assertThat(NotificationPreferences.EVENTS).contains(NotificationPreferences.SHIFTS);
		NotificationPreferences.Channel defaults = NotificationPreferences.defaults().getEvents()
				.get(NotificationPreferences.SHIFTS);
		assertThat(defaults.isEmail()).isTrue();
		assertThat(defaults.isPush()).isTrue();
	}

	@Test
	void storedPreferencesFromBeforeShiftsGetTheDefault() {
		NotificationPreferences old = NotificationPreferences.defaults();
		old.getEvents().remove(NotificationPreferences.SHIFTS);
		assertThat(old.sanitized().deliversPush(NotificationPreferences.SHIFTS)).isTrue();
	}

	@Test
	void onlyThoseWhoSeeTheProjectAreTold() {
		shifts(true, true);
		when(reach.whoCanSee(eq("p-1"), any())).thenReturn(Set.of(ann.getId()));

		service.notifyEach(Notification.Type.SHIFT_ASSIGNED, "p-1", List.of(ann.getId(), bob.getId()),
				"/shifts/events/e-1", reader -> notice());

		assertThat(savedBells(1)).singleElement().satisfies(bell -> {
			assertThat(bell.getUserId()).isEqualTo(ann.getId());
			assertThat(bell.getType()).isEqualTo(Notification.Type.SHIFT_ASSIGNED);
			assertThat(bell.getLink()).isEqualTo("/shifts/events/e-1");
		});
		verify(push, never()).sendToUser(eq(bob.getId()), anyString(), anyString(), any(), any());
	}

	@Test
	void severalNoticesInOneProjectAskWhoSeesItOnceAndInsertOnce() {
		shifts(false, false);
		when(reach.whoCanSee(eq("p-1"), any())).thenReturn(Set.of(ann.getId(), bob.getId()));

		service.notifyEachInProject(Notification.Type.SHIFT_REMINDER, "p-1", List.of(
				new NotificationService.NoticeBatch(List.of(ann.getId()), "/shifts/events/e-1", reader -> notice()),
				new NotificationService.NoticeBatch(List.of(ann.getId(), bob.getId()), "/shifts/events/e-2",
						reader -> notice())));

		verify(reach, org.mockito.Mockito.times(1)).whoCanSee(eq("p-1"), any());
		assertThat(savedBells(1)).extracting(Notification::getUserId, Notification::getLink).containsExactly(
				org.assertj.core.api.Assertions.tuple(ann.getId(), "/shifts/events/e-1"),
				org.assertj.core.api.Assertions.tuple(ann.getId(), "/shifts/events/e-2"),
				org.assertj.core.api.Assertions.tuple(bob.getId(), "/shifts/events/e-2"));
	}

	@Test
	void thePushGetsItsOwnWordsAndTheMailItsOwnButton() {
		shifts(true, true);
		when(reach.whoCanSee(eq("p-1"), any())).thenReturn(Set.of(ann.getId()));

		service.notifyEach(Notification.Type.SHIFT_ASSIGNED, "p-1", List.of(ann.getId()), "/shifts", reader -> notice());

		verify(push).sendToUser(ann.getId(), "Schichten", "Du hast eine neue Schicht.", "/shifts",
				Map.of("type", "SHIFT_ASSIGNED"));
		verify(mail).sendNotification(eq(ann.getEmail()), anyString(), eq("Neue Schicht"),
				eq("Aufbau bei Sommerfest, 21.10.2026, 14:00 bis 18:00"), any(), eq("Schichten öffnen"), eq("de"),
				eq("email.eyebrow.SHIFT_ASSIGNED"));
	}

	@Test
	void switchingShiftsOffKeepsTheBellAndSilencesMailAndPush() {
		shifts(false, false);
		when(reach.whoCanSee(eq("p-1"), any())).thenReturn(Set.of(ann.getId()));

		for (Notification.Type type : shiftTypes()) {
			service.notifyEach(type, "p-1", List.of(ann.getId()), "/shifts", reader -> notice());
		}

		assertThat(savedBells(shiftTypes().size())).hasSize(shiftTypes().size());
		verify(mail, never()).sendNotification(anyString(), anyString(), anyString(), anyString(), any(), any(),
				any(), any());
		verify(push, never()).sendToUser(anyString(), anyString(), anyString(), any(), any());
	}

	@Test
	void everyShiftTypeHasAnEyebrowInEveryLanguage() {
		for (Locale locale : SUPPORTED) {
			ResourceBundle bundle = ResourceBundle.getBundle("email-messages", locale);
			for (Notification.Type type : shiftTypes()) {
				assertThat(bundle.containsKey("email.eyebrow." + type.name())).as("%s %s", locale, type).isTrue();
			}
		}
	}

	@Test
	void theMailRendersTheEyebrowNotItsKey() {
		SpringTemplateEngine engine = EmailFixtures.engine();
		MailService real = new MailService(null, null, null, EmailFixtures.settings(), EmailFixtures.brandLogo());
		for (String locale : List.of("de", "en")) {
			for (Notification.Type type : shiftTypes()) {
				Map<String, Object> model = EmailFixtures.model("email/notification", locale);
				model.put("eyebrowKey", "email.eyebrow." + type.name());
				String html = real.render(engine, EmailFixtures.templateOf("email/notification"), model);
				assertThat(html).as("%s %s", type, locale).doesNotContain("email.eyebrow.").doesNotContain("??");
			}
		}
		Map<String, Object> model = EmailFixtures.model("email/notification", "de");
		model.put("eyebrowKey", "email.eyebrow.SHIFT_REMINDER");
		assertThat(real.render(engine, EmailFixtures.templateOf("email/notification"), model)).contains("Erinnerung");
	}
}
