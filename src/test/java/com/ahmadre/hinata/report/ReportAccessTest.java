package com.ahmadre.hinata.report;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every reporting endpoint took a {@code projectId} from the caller and
 * aggregated over it after checking only that somebody was signed in. Any account
 * could therefore profile any project — issue volume, workflow states, priorities,
 * who is assigned what — and {@code /time-per-project}, which takes no project at
 * all, returned booked effort for the entire organisation.
 *
 * <p>Asserted at the seam rather than over a real database, because the fix is a
 * guard and a query shape, not a result set: the questions are "did it refuse" and
 * "was the query narrowed", and a mock answers both without a container. The
 * complementary {@code SearchAccessMongoTest} does use a real Mongo, because there
 * the fix genuinely is what the query returns.
 */
class ReportAccessTest {

	private static final String MINE = "p-mine";
	private static final String THEIRS = "p-theirs";

	private MongoTemplate mongo;
	private ProjectService projects;
	private CurrentUser currentUser;
	private ReportController controller;

	private final User member = user("u-member", false);

	@BeforeEach
	void setUp() {
		mongo = mock(MongoTemplate.class);
		when(mongo.find(any(Query.class), eq(com.ahmadre.hinata.issue.Issue.class)))
				.thenReturn(List.of());
		when(mongo.find(any(Query.class), eq(WorkItem.class))).thenReturn(List.of());

		projects = mock(ProjectService.class);
		when(projects.findOptional(MINE)).thenReturn(Optional.of(project(MINE)));
		when(projects.findOptional(THEIRS)).thenReturn(Optional.of(project(THEIRS)));
		when(projects.findOptional("p-missing")).thenReturn(Optional.empty());
		when(projects.visibleTo(member)).thenReturn(List.of(project(MINE)));
		// The real service throws for a project the user cannot reach.
		org.mockito.Mockito.doThrow(ApiException.forbidden("error.project.notMember"))
				.when(projects).assertMember(argThatIs(THEIRS), eq(member));

		currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(member);

		controller = new ReportController(mongo, currentUser, projects);
	}

	// --- the guard ------------------------------------------------------------

	@Test
	void aNonMemberCannotProfileAnotherProjectsStates() {
		assertThatThrownBy(() -> controller.issuesByState(THEIRS))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("notMember");
	}

	@Test
	void aNonMemberCannotProfileAnotherProjectsAssignees() {
		assertThatThrownBy(() -> controller.issuesByAssignee(THEIRS))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void aNonMemberCannotProfileAnotherProjectsPriorities() {
		assertThatThrownBy(() -> controller.issuesByPriority(THEIRS))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void aNonMemberCannotReadAnotherProjectsTrend() {
		assertThatThrownBy(() -> controller.createdVsResolved(THEIRS, 30))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void aNonMemberCannotReadAnotherProjectsTimeBreakdown() {
		assertThatThrownBy(() -> controller.timePerActivity(THEIRS, day(1), day(30)))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * The refusal has to happen before the aggregation, not alongside it: a guard
	 * that throws after the read has already answered the question the caller asked.
	 */
	@Test
	void theRefusalHappensBeforeAnythingIsRead() {
		assertThatThrownBy(() -> controller.issuesByState(THEIRS))
				.isInstanceOf(ApiException.class);

		verify(mongo, never()).find(any(Query.class), any(Class.class));
	}

	@Test
	void anUnknownProjectIsNotFound() {
		assertThatThrownBy(() -> controller.issuesByState("p-missing"))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("notFound");
	}

	// --- the endpoint that names no project -------------------------------------

	/**
	 * {@code /time-per-project} cannot be secured by checking its parameter, because
	 * it has none. The restriction has to be in the query, and it has to be there
	 * rather than applied to the results — summing the organisation and then hiding
	 * rows still reads every work item in the database.
	 */
	@Test
	void timePerProjectIsRestrictedToTheProjectsTheCallerReaches() {
		controller.timePerProject(day(1), day(30));

		ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(captor.capture(), eq(WorkItem.class));
		// toString rather than toJson: the query carries LocalDate bounds and BSON
		// has no codec for those without the application's converters registered.
		String query = captor.getValue().getQueryObject().toString();

		assertThat(query).contains(MINE);
		assertThat(query).doesNotContain(THEIRS);
	}

	@Test
	void timePerProjectForSomeoneWhoReachesNothingAsksForNothing() {
		when(projects.visibleTo(member)).thenReturn(List.of());

		controller.timePerProject(day(1), day(30));

		ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(captor.capture(), eq(WorkItem.class));
		String query = captor.getValue().getQueryObject().toString();
		assertThat(query).contains("$in").doesNotContain(MINE).doesNotContain(THEIRS);
	}

	// --- what must still work -----------------------------------------------------

	@Test
	void aMemberStillReadsTheirOwnProject() {
		assertThatCode(() -> controller.issuesByState(MINE)).doesNotThrowAnyException();
		assertThatCode(() -> controller.issuesByAssignee(MINE)).doesNotThrowAnyException();
		assertThatCode(() -> controller.issuesByPriority(MINE)).doesNotThrowAnyException();
		assertThatCode(() -> controller.createdVsResolved(MINE, 30)).doesNotThrowAnyException();
		assertThatCode(() -> controller.timePerActivity(MINE, day(1), day(30)))
				.doesNotThrowAnyException();
	}

	/** An admin reaches every project, so their breakdown is not narrowed at all. */
	@Test
	void anAdminSeesTheWholeWorkspaceTimeBreakdown() {
		User admin = user("u-admin", true);
		when(currentUser.require()).thenReturn(admin);

		controller.timePerProject(day(1), day(30));

		ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(captor.capture(), eq(WorkItem.class));
		// No narrowing clause at all — an admin reaches everything, and building an
		// $in over every project id would be the same answer computed expensively.
		assertThat(captor.getValue().getQueryObject().toString()).doesNotContain("projectId");
	}

	// --- helpers --------------------------------------------------------------------

	/** Matches the {@link Project} carrying [id], for stubbing {@code assertMember}. */
	private static Project argThatIs(String id) {
		return org.mockito.ArgumentMatchers.argThat(
				project -> project != null && id.equals(project.getId()));
	}

	private static Project project(String id) {
		return Project.builder().id(id).name(id).key(id).memberIds(List.of()).build();
	}

	private static User user(String id, boolean admin) {
		return User.builder().id(id).displayName(id).active(true)
				.roles(admin ? Set.of(Role.ADMIN) : Set.of(Role.MEMBER)).build();
	}

	private static LocalDate day(int dayOfMonth) {
		return LocalDate.of(2026, 8, dayOfMonth);
	}
}
