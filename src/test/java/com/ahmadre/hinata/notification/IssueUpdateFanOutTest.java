package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.issue.Issue;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The delivery side of watching an issue.
 *
 * <p>These test the path that actually decides who hears about a change, not the
 * cleanup that tidies watcher lists afterwards. That distinction is the whole
 * security argument: cleanup is best-effort and can miss a path, so a watcher
 * who lost access has to be stopped <em>here</em>, on every single delivery.
 */
class IssueUpdateFanOutTest {

	private UserRepository users;
	private NotificationRepository notifications;
	private MailService mail;
	private PushService push;
	private ProjectReach reach;
	private IssueDigestService digests;
	private NotificationService service;

	private static final String PROJECT = "p1";

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		notifications = mock(NotificationRepository.class);
		mail = mock(MailService.class);
		push = mock(PushService.class);
		reach = mock(ProjectReach.class);
		digests = mock(IssueDigestService.class);
		lenient().when(users.findById(anyString())).thenReturn(Optional.empty());
		// Nobody reaches the project unless a test says so.
		lenient().when(reach.whoCanSee(anyString(), any())).thenReturn(Set.of());
		lenient().when(reach.canSee(anyString(), any(User.class))).thenReturn(false);
		// A queued mail counts as handled, exactly as the real service reports it.
		lenient().when(digests.queue(any(), any(), anyList())).thenReturn(true);
		service = new NotificationService(notifications, users, mail, push,
				mock(GatewayService.class), new RichTextService(), reach,
				new IssueChangeRenderer(users, mock(SprintRepository.class),
						mock(IssueRepository.class), mock(ProjectRepository.class)),
				digests);
	}

	// --- fixtures -------------------------------------------------------------

	private User user(String id, NotificationPreferences prefs) {
		User user = User.builder().id(id).displayName(id).email(id + "@example.org")
				.active(true).notificationPreferences(prefs).build();
		when(users.findById(id)).thenReturn(Optional.of(user));
		return user;
	}

	private User user(String id) {
		return user(id, NotificationPreferences.defaults());
	}

	/** Defaults with one event's e-mail flipped — the switch these tests turn. */
	private NotificationPreferences withEmail(String event, boolean on) {
		NotificationPreferences prefs = NotificationPreferences.defaults();
		prefs.getEvents().get(event).setEmail(on);
		return prefs;
	}

	private Issue issue(List<String> watchers, List<String> assignees, String reporter) {
		return Issue.builder().id("i1").projectId(PROJECT).readableId("HIN-42")
				.title("Login bug")
				.watcherIds(new ArrayList<>(watchers))
				.assigneeIds(new ArrayList<>(assignees))
				.reporterId(reporter)
				.build();
	}

	/** Whoever the project lets through, out of the candidates asked about. */
	private void projectVisibleTo(String... userIds) {
		Set<String> allowed = Set.of(userIds);
		when(reach.whoCanSee(eq(PROJECT), any())).thenAnswer(call -> {
			Collection<?> candidates = call.getArgument(1);
			return candidates.stream().filter(allowed::contains)
					.map(String.class::cast).collect(java.util.stream.Collectors.toSet());
		});
		// The same answer for the single-user overload that decides the CTA link.
		when(reach.canSee(eq(PROJECT), any(User.class))).thenAnswer(call -> {
			User user = call.getArgument(1);
			return user != null && allowed.contains(user.getId());
		});
	}

	private static final List<FieldChange> STATE_CHANGE =
			List.of(new FieldChange(IssueChangeDiff.STATE, "Open", "In Progress"));

	private List<Notification> saved() {
		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notifications, org.mockito.Mockito.atLeast(0)).save(captor.capture());
		return captor.getAllValues();
	}

	// --- access is re-decided on every delivery -------------------------------

	@Test
	void aWatcherWhoLeftTheProjectHearsNothingMore() {
		user("u-gone");
		projectVisibleTo(/* nobody */);

		service.notifyUpdated(issue(List.of("u-gone"), List.of(), null), STATE_CHANGE, user("u-actor"));

		verify(notifications, never()).save(any());
		verify(push, never()).sendToUser(anyString(), anyString(), anyString(), any());
	}

	/** A team grant is access, so it is enough to keep hearing about the issue. */
	@Test
	void aWatcherWhoReachesTheProjectThroughATeamStillHears() {
		user("u-team");
		projectVisibleTo("u-team");

		service.notifyUpdated(issue(List.of("u-team"), List.of(), null), STATE_CHANGE, user("u-actor"));

		assertThat(saved()).singleElement()
				.returns("u-team", Notification::getUserId)
				.returns(Notification.Type.ISSUE_UPDATED, Notification::getType);
	}

	/**
	 * Regression for the mail-ingest reporter: an issue raised by e-mail is
	 * attributed to the sender's account, which may not belong to the project.
	 * They must still be told their own request moved — only the dead CTA is
	 * dropped, exactly as {@code linkFor} has always done it.
	 */
	@Test
	void theIngestReporterWithoutProjectAccessIsStillNotifiedWithoutACta() {
		user("u-ext", withEmail("status", true));
		projectVisibleTo(/* nobody */);

		service.notifyUpdated(issue(List.of(), List.of(), "u-ext"), STATE_CHANGE, user("u-actor"));

		assertThat(saved()).singleElement()
				.returns("u-ext", Notification::getUserId)
				.returns(null, Notification::getLink);
		verify(mail).sendNotification(eq("u-ext@example.org"), anyString(), anyString(), anyString(),
				isNull(), anyString(), anyString(), anyString());
	}

	// --- one notice per update ------------------------------------------------

	/**
	 * The regression this feature exists for: the old {@code if/else if} chain let
	 * an assignment swallow a state change, so a save that did both announced one
	 * of them. One save, one notice, both changes in it.
	 */
	@Test
	void anAssignmentAndAStateChangeInOneSaveProduceOneNoticeCarryingBoth() {
		user("u-watch");
		projectVisibleTo("u-watch");
		List<FieldChange> both = List.of(
				new FieldChange(IssueChangeDiff.ASSIGNEES, null, "u2"),
				new FieldChange(IssueChangeDiff.STATE, "Open", "In Progress"));

		service.notifyUpdated(issue(List.of("u-watch"), List.of(), null), both, user("u-actor"));

		// The delta's VALUES, not the field's label: what this test protects is that
		// one save produces one notice carrying both changes, and it must not go red
		// because somebody renamed the word "Assignees" in the renderer.
		assertThat(saved()).singleElement().satisfies(n ->
				assertThat(n.getBody()).contains("+u2").contains("Open → In Progress"));
	}

	@Test
	void anEmptyChangeListNotifiesNobody() {
		user("u-watch");
		projectVisibleTo("u-watch");

		service.notifyUpdated(issue(List.of("u-watch"), List.of(), null), List.of(), user("u-actor"));

		verify(notifications, never()).save(any());
	}

	/** Two edits that cancel each other out are not a change anyone should hear. */
	@Test
	void changesThatCancelOutNotifyNobody() {
		user("u-watch");
		projectVisibleTo("u-watch");
		List<FieldChange> thereAndBack = List.of(
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"),
				new FieldChange(IssueChangeDiff.PRIORITY, "MAJOR", "NORMAL"));

		service.notifyUpdated(issue(List.of("u-watch"), List.of(), null), thereAndBack,
				user("u-actor"));

		verify(notifications, never()).save(any());
	}

	@Test
	void theActorIsNeverNotifiedAboutTheirOwnChange() {
		User actor = user("u-actor");
		projectVisibleTo("u-actor");

		service.notifyUpdated(issue(List.of("u-actor"), List.of("u-actor"), "u-actor"),
				STATE_CHANGE, actor);

		verify(notifications, never()).save(any());
	}

	/**
	 * Deduplication, the same rule {@code notifyComment} applies: a user who was
	 * just told "this is yours" does not also get "the assignee changed".
	 */
	@Test
	void aRecipientAlreadyToldAboutTheAssignmentIsNotToldTwice() {
		user("u-new");
		user("u-watch");
		projectVisibleTo("u-new", "u-watch");
		Issue issue = issue(List.of("u-watch"), List.of("u-new"), null);

		service.notifyUpdated(issue, STATE_CHANGE, user("u-actor"), Set.of("u-new"));

		assertThat(saved()).extracting(Notification::getUserId).containsExactly("u-watch");
	}

	// --- per-recipient preference event ---------------------------------------

	/**
	 * The same notification reaches two people for two different reasons, so each
	 * must be switchable by the reason they actually have. Here both users have
	 * push on for their own event and off for the other's: if the fan-out picked
	 * one event id for the whole delivery, one of them would be wrong.
	 */
	@Test
	void aWatcherIsGatedByWatchingAndAnAssigneeByStatus() {
		NotificationPreferences watchingOnly = NotificationPreferences.defaults();
		watchingOnly.getEvents().get("status").setPush(false);
		watchingOnly.getEvents().get(NotificationPreferences.WATCHING).setPush(true);
		NotificationPreferences statusOnly = NotificationPreferences.defaults();
		statusOnly.getEvents().get("status").setPush(true);
		statusOnly.getEvents().get(NotificationPreferences.WATCHING).setPush(false);
		user("u-watch", watchingOnly);
		user("u-assignee", statusOnly);
		projectVisibleTo("u-watch", "u-assignee");

		service.notifyUpdated(issue(List.of("u-watch"), List.of("u-assignee"), null),
				STATE_CHANGE, user("u-actor"));

		verify(push).sendToUser(eq("u-watch"), anyString(), anyString(), eq("/issues/HIN-42"));
		verify(push).sendToUser(eq("u-assignee"), anyString(), anyString(), eq("/issues/HIN-42"));
	}

	@Test
	void aWatcherWhoSwitchedWatchingOffGetsNothingOnThatChannel() {
		NotificationPreferences off = NotificationPreferences.defaults();
		off.getEvents().get(NotificationPreferences.WATCHING).setEmail(false);
		off.getEvents().get(NotificationPreferences.WATCHING).setPush(false);
		user("u-watch", off);
		projectVisibleTo("u-watch");

		service.notifyUpdated(issue(List.of("u-watch"), List.of(), null), STATE_CHANGE,
				user("u-actor"));

		verify(digests, never()).queue(any(), any(), anyList());
		verify(push, never()).sendToUser(anyString(), anyString(), anyString(), any());
		// The bell entry is always recorded — that is not a channel the user gates.
		assertThat(saved()).extracting(Notification::getUserId).containsExactly("u-watch");
	}

	// --- bundling -------------------------------------------------------------

	/**
	 * Only the watcher stream is bundled. An assignee's mail keeps arriving at the
	 * moment of the change, as it always has — bundling exists to stop a
	 * subscription from punishing a mailbox, not to slow down assignments.
	 */
	@Test
	void aWatchersMailIsBundledWhileAnAssigneesIsSentAtOnce() {
		user("u-watch");
		user("u-assignee", withEmail("status", true));
		projectVisibleTo("u-watch", "u-assignee");

		service.notifyUpdated(issue(List.of("u-watch"), List.of("u-assignee"), null),
				STATE_CHANGE, user("u-actor"));

		verify(digests).queue(any(), argThatIs("u-watch"), anyList());
		verify(mail).sendNotification(eq("u-assignee@example.org"), anyString(), anyString(),
				anyString(), any(), anyString(), anyString(), anyString());
		verify(mail, never()).sendNotification(eq("u-watch@example.org"), anyString(), anyString(),
				anyString(), any(), anyString(), anyString(), anyString());
	}

	private static User argThatIs(String id) {
		return org.mockito.ArgumentMatchers.argThat(user -> user != null && id.equals(user.getId()));
	}
}
