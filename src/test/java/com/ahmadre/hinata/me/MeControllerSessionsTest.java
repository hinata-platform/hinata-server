package com.ahmadre.hinata.me;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.issue.IssueWatchService;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The device-session list is paginated because it only ever grows: every
 * browser, every phone and every reinstall adds a row, and the settings screen
 * showed all of them at once.
 */
class MeControllerSessionsTest {

	private static final String USER_ID = "u-1";
	private static final String CURRENT_SESSION = "s-current";

	private MeService me;
	private MeController controller;

	@BeforeEach
	void setUp() {
		me = mock(MeService.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.requireId()).thenReturn(USER_ID);
		when(currentUser.currentSessionId()).thenReturn(CURRENT_SESSION);

		ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
		messages.setBasename("messages");
		messages.setDefaultEncoding("UTF-8");
		messages.setFallbackToSystemLocale(false);

		controller = new MeController(me, currentUser, mock(UserEvents.class),
				mock(DataExportPdfService.class), mock(UserService.class),
				mock(IssueWatchService.class), mock(SecurityPolicy.class), mock(JwtDecoder.class),
				mock(SettingsService.class), mock(BrandLogoService.class), messages);
	}

	private RefreshSession session(String id) {
		return RefreshSession.builder()
				.id(id)
				.userId(USER_ID)
				.kind(RefreshSession.Kind.desktop)
				.lastActiveAt(Instant.parse("2026-09-04T00:00:00Z"))
				.build();
	}

	private void stub(Page<RefreshSession> page) {
		when(me.sessions(eq(USER_ID), any(Pageable.class))).thenReturn(page);
	}

	@Test
	void marksOnlyTheCallersOwnDeviceAsCurrent() {
		stub(new PageImpl<>(List.of(session(CURRENT_SESSION), session("s-other")),
				PageRequest.of(0, 25), 2));

		List<MeController.SessionDto> rows = controller.sessions(0, 25).getContent();

		assertThat(rows).extracting(MeController.SessionDto::id)
				.containsExactly(CURRENT_SESSION, "s-other");
		assertThat(rows).extracting(MeController.SessionDto::current)
				.containsExactly(true, false);
	}

	@Test
	void reportsTheFullCountSoTheClientKnowsWhatItIsNotShowing() {
		// The screen shows the first few rows and offers to expand: without the
		// total it cannot say how many are behind the expander, and cannot tell
		// "that is all of them" from "there are ninety more".
		stub(new PageImpl<>(List.of(session("s-1"), session("s-2")), PageRequest.of(0, 2), 15));

		Page<MeController.SessionDto> page = controller.sessions(0, 2);

		assertThat(page.getContent()).hasSize(2);
		assertThat(page.getTotalElements()).isEqualTo(15);
	}

	@Test
	void clampsAnOversizedPageRatherThanServingTheWholeTable() {
		// Otherwise ?size=100000 is an invitation to read every session an
		// account ever opened in one request.
		stub(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		controller.sessions(0, 100_000);

		var pageable = forClass(Pageable.class);
		verify(me).sessions(eq(USER_ID), pageable.capture());
		assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
	}

	@Test
	void refusesToBeTurnedIntoAStackTraceGenerator() {
		// PageRequest.of throws on a negative page or a zero size, and an
		// unhandled IllegalArgumentException is a 500 with a stack trace in the
		// log — one authenticated caller could fill the error log from a query
		// string. Both bounds are clamped, so neither is reachable.
		stub(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

		controller.sessions(-5, 0);

		var pageable = forClass(Pageable.class);
		verify(me).sessions(eq(USER_ID), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isZero();
		assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
	}

	@Test
	void passesThePageThroughSoLaterPagesAreReachable() {
		stub(new PageImpl<>(List.of(), PageRequest.of(3, 25), 0));

		controller.sessions(3, 25);

		var pageable = forClass(Pageable.class);
		verify(me).sessions(eq(USER_ID), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
	}
}
