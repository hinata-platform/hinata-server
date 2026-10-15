package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link IssueReach} answers "may this user see this issue" twice, like {@link ProjectReach}:
 * once per user and once in bulk for the notification fan-out. This test pins both answers to
 * each other for every relationship a person can have to an issue, so a rule added to one of
 * them alone turns the build red instead of quietly mailing someone an issue they cannot open.
 */
class IssueReachTest {

	private static final String PROJECT = "p1";
	private static final Set<String> MEMBERS = Set.of("member", "memberAndParticipant");

	private ProjectReach projects;
	private IssueReach reach;
	private Issue issue;

	@BeforeEach
	void setUp() {
		projects = mock(ProjectReach.class);
		reach = new IssueReach(projects);
		issue = Issue.builder().id("i1").projectId(PROJECT)
				.participantIds(new ArrayList<>(List.of("participant", "memberAndParticipant")))
				.build();
		when(projects.canSee(eq(PROJECT), any(User.class)))
				.thenAnswer(invocation -> MEMBERS.contains(((User) invocation.getArgument(1)).getId()));
		when(projects.whoCanSee(eq(PROJECT), anyCollection())).thenAnswer(invocation -> {
			Set<String> allowed = new HashSet<>((Collection<String>) invocation.getArgument(1));
			allowed.retainAll(MEMBERS);
			return allowed;
		});
	}

	@Test
	void aProjectMemberSeesTheIssue() {
		assertThat(reach.canSee(issue, user("member"))).isTrue();
	}

	@Test
	void aParticipantSeesTheIssueWithoutProjectReach() {
		assertThat(reach.canSee(issue, user("participant"))).isTrue();
	}

	@Test
	void anOutsiderDoesNot() {
		assertThat(reach.canSee(issue, user("outsider"))).isFalse();
		assertThat(reach.canSee(null, user("outsider"))).isFalse();
		assertThat(reach.canSee(issue, null)).isFalse();
	}

	@Test
	void whoCanSeeAgreesWithCanSeeForEveryCombination() {
		List<String> everyone = List.of("member", "participant", "memberAndParticipant", "outsider");

		Set<String> bulk = reach.whoCanSee(issue, everyone);

		for (String id : everyone) {
			assertThat(bulk.contains(id)).as(id).isEqualTo(reach.canSee(issue, user(id)));
		}
		assertThat(bulk).containsExactlyInAnyOrder("member", "participant", "memberAndParticipant");
	}

	@Test
	void whoCanSeeOfNothingIsEmpty() {
		assertThat(reach.whoCanSee(issue, List.of())).isEmpty();
		assertThat(reach.whoCanSee(null, List.of("member"))).isEmpty();
	}

	@Test
	void isParticipantOnlyIsFalseForMembersEvenWhenListed() {
		assertThat(reach.isParticipantOnly(issue, user("participant"))).isTrue();
		assertThat(reach.isParticipantOnly(issue, user("memberAndParticipant"))).isFalse();
		assertThat(reach.isParticipantOnly(issue, user("member"))).isFalse();
		assertThat(reach.isParticipantOnly(issue, user("outsider"))).isFalse();
	}

	@Test
	void anIssueWithoutAParticipantListBehavesAsEmpty() {
		Issue legacy = Issue.builder().id("i2").projectId(PROJECT).participantIds(null).build();

		assertThat(reach.canSee(legacy, user("participant"))).isFalse();
		assertThat(reach.whoCanSee(legacy, List.of("participant", "member"))).containsExactly("member");
	}

	private static User user(String id) {
		return User.builder().id(id).username(id).build();
	}
}
