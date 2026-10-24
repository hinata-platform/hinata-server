package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.setup.OperatorContact;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** With no admin left to tell, the instance's contact gets the word by mail (HIN-170). */
class ModerationNoticesTest {

	private final NotificationService notifications = mock(NotificationService.class);
	private final CaseAccess access = mock(CaseAccess.class);
	private final OperatorContact contact = mock(OperatorContact.class);
	private final SettingsService settings = mock(SettingsService.class);
	private final ModerationNotices notices = new ModerationNotices(notifications, access, mock(UserWords.class),
			contact, settings);
	private final ModerationCase moderationCase = ModerationCase.builder().id("case-1").build();

	@Test
	void withNoAdminLeftTheContactGetsAMailInTheInstancesLanguage() {
		when(access.admins()).thenReturn(List.of());
		when(access.eligibleAdmins(moderationCase, List.of())).thenReturn(List.of());
		when(contact.current()).thenReturn(new OperatorContact.Contact("Fachschaft", "moderation@example.org", null));
		when(settings.instanceLocale()).thenReturn(Locale.GERMAN);

		notices.adminAlert(moderationCase, "moderation.alert.nobody");

		verify(notifications).mailAddress("moderation@example.org", Locale.GERMAN, "moderation.alert.nobody.title",
				"moderation.alert.nobody.body");
		verify(notifications, never()).notifyPeople(any(), any(), any(), any(), any());
	}

	@Test
	void withNoAdminAndNoContactNobodyIsWritten() {
		when(access.admins()).thenReturn(List.of());
		when(access.eligibleAdmins(moderationCase, List.of())).thenReturn(List.of());
		when(contact.current()).thenReturn(new OperatorContact.Contact(null, null, null));

		notices.adminAlert(moderationCase, "moderation.alert.nobody");

		verifyNoInteractions(notifications);
	}
}
