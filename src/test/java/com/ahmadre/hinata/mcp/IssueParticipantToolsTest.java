package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueParticipantService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The MCP side of sharing an issue: the write scope, then the service with all of its rules. */
class IssueParticipantToolsTest {

	private IssueParticipantService participants;
	private ScopeGuard scopes;
	private User me;
	private IssueParticipantTools tools;

	@BeforeEach
	void setUp() {
		participants = mock(IssueParticipantService.class);
		scopes = mock(ScopeGuard.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		me = User.builder().id("u1").displayName("Agent").build();
		when(currentUser.require()).thenReturn(me);
		tools = new IssueParticipantTools(participants, currentUser, scopes);
	}

	@Test
	void addParticipantToolDelegatesToTheService() {
		tools.add_participant("HIN-1", "u9");

		var order = inOrder(scopes, participants);
		order.verify(scopes).require(Scopes.ISSUES_WRITE);
		order.verify(participants).add("HIN-1", "u9", me);
	}

	@Test
	void removeParticipantToolDelegatesToTheService() {
		tools.remove_participant("HIN-1", "u9");

		verify(scopes).require(Scopes.ISSUES_WRITE);
		verify(participants).remove("HIN-1", "u9", me);
	}

	@Test
	void getIssueShowsParticipantIds() {
		Issue issue = Issue.builder().id("i1").readableId("HIN-1")
				.participantIds(new ArrayList<>(List.of("u9"))).build();

		assertThat(McpViews.IssueView.of(issue).participantIds()).containsExactly("u9");
	}
}
