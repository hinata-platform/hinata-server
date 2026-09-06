package com.ahmadre.hinata.me;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
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

	/**
	 * The published app casts this response to a list. Paginating it made that
	 * cast throw, and because its settings screen loads the account, the sessions,
	 * the teams and the projects together, the whole page failed — the account did
	 * not fail to load, it was never asked for. Until 10.2.1 is out of the field,
	 * a request with neither bound answers the shape that client can read.
	 */
	@Test
	void aRequestWithoutBoundsAnswersTheArrayThePublishedAppExpects() {
		stub(new PageImpl<>(List.of(session(CURRENT_SESSION), session("s-other")),
				PageRequest.of(0, 100), 2));

		List<MeController.SessionDto> rows = controller.sessionsLegacy();

		assertThat(rows).extracting(MeController.SessionDto::id)
				.containsExactly(CURRENT_SESSION, "s-other");
		assertThat(rows).extracting(MeController.SessionDto::current)
				.containsExactly(true, false);
	}

	/** Bounded all the same: an unbounded read of this table is what pagination
	 *  was introduced to stop. */
	@Test
	void theLegacyArrayIsStillBounded() {
		stub(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		controller.sessionsLegacy();

		var pageable = forClass(Pageable.class);
		verify(me).sessions(eq(USER_ID), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isZero();
		assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
	}

	/**
	 * The compatibility array cannot be removed on a date — it can be removed when
	 * nothing calls it any more, and this line is how anyone finds that out. It is
	 * load-bearing for HIN-74, so it is asserted rather than assumed.
	 */
	@Test
	void theLegacyArraySaysSoInTheLogTheFirstTimeItIsUsed() {
		stub(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		List<String> lines = captureLog(() -> {
			controller.sessionsLegacy();
			controller.sessionsLegacy();
			controller.sessionsLegacy();
		});

		// Once per run, not once per call: the question it answers is whether
		// anyone at all is still on the old client, and a per-call line would bury
		// that answer in the noise it creates.
		assertThat(lines).containsExactly(MeController.LEGACY_SESSION_MARKER);
	}

	/** The paged form is what the current app sends, and it says nothing at all. */
	@Test
	void thePagedFormIsSilent() {
		stub(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

		assertThat(captureLog(() -> controller.sessions(0, 25))).isEmpty();
	}

	/** Runs [action] with the controller's logger captured, and returns its lines. */
	private List<String> captureLog(Runnable action) {
		Logger logger = (Logger) LoggerFactory.getLogger(MeController.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
			return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
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
