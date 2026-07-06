package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers the plain-text {@code preview()} teaser surfaced in push/e-mail bodies. */
class NotificationServiceTest {

	private UserRepository users;
	private NotificationService service;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		lenient().when(users.findById(anyString())).thenReturn(Optional.empty());
		service = new NotificationService(mock(NotificationRepository.class), users,
				mock(MailService.class), mock(PushService.class), mock(GatewayService.class));
	}

	@Test
	void resolvesMentionTokensToDisplayName() {
		User rebar = User.builder().id("u1").displayName("Rebar").build();
		when(users.findById("u1")).thenReturn(Optional.of(rebar));

		assertThat(service.preview("{{user:u1}} hi was geht?")).isEqualTo("@Rebar hi was geht?");
	}

	@Test
	void unknownMentionFallsBackToNeutralName() {
		assertThat(service.preview("{{user:ghost}} hello")).isEqualTo("@someone hello");
	}

	@Test
	void stripsMarkdownAndCollapsesWhitespace() {
		String raw = "## Heading\n\n- **bold** item\n- `code` and [link](https://x.de)";
		assertThat(service.preview(raw)).isEqualTo("Heading bold item code and link");
	}

	@Test
	void truncatesLongTextWithEllipsis() {
		String raw = "x".repeat(500);
		String out = service.preview(raw);
		assertThat(out).hasSize(160).endsWith("…");
	}

	@Test
	void handlesNullAndBlank() {
		assertThat(service.preview(null)).isEmpty();
		assertThat(service.preview("   \n\t ")).isEmpty();
	}
}
