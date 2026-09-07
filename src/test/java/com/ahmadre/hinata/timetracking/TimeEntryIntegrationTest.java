package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entries that stand on their own: what a valid one is, where it may be filed,
 * and what the personal list does and does not show.
 *
 * <p>Against a real MongoDB because the two things worth doubting are both the
 * database's: the paging has to be stable under a sort with a tiebreaker (a
 * mocked repository would return whatever the mock was told), and the filters
 * have to reach the query rather than being applied to a page that was already
 * chosen without them.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Import(TimeEntryIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeEntryIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

	@TestConfiguration
	static class FrozenClock {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;

	private User owner;
	private User peer;
	private User outsider;
	private User admin;
	private Project project;
	private Project other;
	private Issue issue;
	private Issue otherIssue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"running_timers", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		owner = user("owner", Role.MEMBER);
		peer = user("peer", Role.MEMBER);
		outsider = user("outsider", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
		project = project("HIN", "Hinata", owner, peer);
		other = project("SEC", "Secret", peer);
		issue = issue(project, "HIN-1", 1);
		otherIssue = issue(other, "SEC-1", 1);
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").build());
	}

	private Project project(String key, String name, User... members) {
		List<String> ids = new ArrayList<>(new HashSet<>(java.util.Arrays.stream(members)
				.map(User::getId).toList()));
		return projects.save(Project.builder().key(key).name(name).leadId(members[0].getId())
				.leadIds(new ArrayList<>(List.of(members[0].getId())))
				.memberIds(ids).build());
	}

	private Issue issue(Project in, String readableId, int number) {
		return issueRepository.save(Issue.builder().projectId(in.getId()).readableId(readableId)
				.numberInProject(number).title(readableId).state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private WorkItem create(TimeTrackingService.NewEntry draft, User as) {
		return timeTracking.create(draft, WorkItem.Source.APP, as);
	}

	/** A minute-accurate instant on the frozen day. */
	private static Instant at(int hour, int minute) {
		return TODAY.atTime(hour, minute).toInstant(ZoneOffset.UTC);
	}

	private static TimeTrackingService.NewEntry spanning(String projectId, String issueId,
			int fromHour, int toHour) {
		return new TimeTrackingService.NewEntry(projectId, issueId, null, null, null, "work",
				at(fromHour, 0), at(toHour, 0), null, null);
	}

	private void assertStatus(ThrowingCallable call, HttpStatus status, String messageKey) {
		assertThatThrownBy(call)
				.isInstanceOf(ApiException.class)
				.hasMessage(messageKey)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(status);
	}

	// --- the two shapes an entry comes in --------------------------------------

	@Test
	@DisplayName("a start and an end define the duration and the day")
	void anIntervalEntry() {
		WorkItem entry = create(spanning(project.getId(), issue.getId(), 9, 10), owner);

		assertThat(entry.getDurationMinutes()).isEqualTo(60);
		assertThat(entry.getDate()).isEqualTo(TODAY);
		assertThat(entry.getStartedAt()).isEqualTo(at(9, 0));
		assertThat(entry.getEndedAt()).isEqualTo(at(10, 0));
		assertThat(entry.getUserId()).isEqualTo(owner.getId());
		// The derived counter moved, exactly as it does through the 1.x route.
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes())
				.isEqualTo(60);
	}

	@Test
	@DisplayName("a plain duration on a day is an entry too")
	void aDurationOnlyEntry() {
		WorkItem entry = create(new TimeTrackingService.NewEntry(project.getId(), issue.getId(),
				45, TODAY.minusDays(1), "Testing", "read the spec", null, null, null, null), owner);

		assertThat(entry.getDurationMinutes()).isEqualTo(45);
		assertThat(entry.getDate()).isEqualTo(TODAY.minusDays(1));
		assertThat(entry.getStartedAt()).isNull();
		assertThat(entry.getEndedAt()).isNull();
		assertThat(entry.getActivityType()).isEqualTo("Testing");
	}

	@Test
	@DisplayName("an end before the start is refused, and so is more than a day")
	void impossibleIntervals() {
		assertStatus(() -> create(spanning(project.getId(), issue.getId(), 11, 9), owner),
				HttpStatus.BAD_REQUEST, "error.time.invalidDuration");
		// Exactly 24h + 1 minute.
		assertStatus(() -> create(new TimeTrackingService.NewEntry(project.getId(), issue.getId(),
						null, null, null, null, at(0, 0), at(0, 0).plusSeconds(24 * 3600 + 60),
						null, null), owner),
				HttpStatus.BAD_REQUEST, "error.time.invalidDuration");
		// And an interval of no length at all: an entry is at least a minute.
		assertStatus(() -> create(spanning(project.getId(), issue.getId(), 9, 9), owner),
				HttpStatus.BAD_REQUEST, "error.time.invalidDuration");
		assertThat(workItems.count()).isZero();
	}

	@Test
	@DisplayName("an entry with no duration at all is refused")
	void nothingToRecord() {
		assertStatus(() -> create(new TimeTrackingService.NewEntry(project.getId(), null, null,
						TODAY, null, "thinking", null, null, null, null), owner),
				HttpStatus.BAD_REQUEST, "error.time.invalidDuration");
	}

	@Test
	@DisplayName("the date rules apply here as much as on the 1.x route")
	void theDayIsStillChecked() {
		assertStatus(() -> create(new TimeTrackingService.NewEntry(null, null, 30,
						TODAY.plusDays(1), null, null, null, null, null, null), owner),
				HttpStatus.BAD_REQUEST, "error.time.dateInFuture");
		assertStatus(() -> create(new TimeTrackingService.NewEntry(null, null, 30,
						TODAY.minusDays(400), null, null, null, null, null, null), owner),
				HttpStatus.BAD_REQUEST, "error.time.dateTooOld");
	}

	// --- where an entry may be filed --------------------------------------------

	@Test
	@DisplayName("an entry may belong to no project at all")
	void unfiledEntriesAreAllowed() {
		WorkItem entry = create(new TimeTrackingService.NewEntry(null, null, 60, TODAY, "Meeting",
				"retro", null, null, null, null), owner);

		assertThat(entry.getProjectId()).isNull();
		assertThat(entry.getIssueId()).isNull();
		assertThat(entry.getUserId()).isEqualTo(owner.getId());
	}

	@Test
	@DisplayName("an entry may name a project without naming an issue")
	void projectTimeWithoutAnIssue() {
		WorkItem entry = create(new TimeTrackingService.NewEntry(project.getId(), null, 30, TODAY,
				null, "planning", null, null, null, null), owner);

		assertThat(entry.getProjectId()).isEqualTo(project.getId());
		assertThat(entry.getIssueId()).isNull();
	}

	@Test
	@DisplayName("filing under a project you cannot see is refused")
	void aForeignProjectIsRefused() {
		assertStatus(() -> create(new TimeTrackingService.NewEntry(other.getId(), null, 30, TODAY,
						null, null, null, null, null, null), outsider),
				HttpStatus.FORBIDDEN, "error.project.notMember");
		assertThat(workItems.count()).isZero();
	}

	@Test
	@DisplayName("an issue from a different project than the one named is refused")
	void theIssueAndTheProjectMustAgree() {
		// peer can see both projects, so this is not an access failure — it is an
		// entry that would be filed under a project its issue is not in, and every
		// report would then disagree with the issue page.
		assertStatus(() -> create(new TimeTrackingService.NewEntry(project.getId(),
						otherIssue.getId(), 30, TODAY, null, null, null, null, null, null), peer),
				HttpStatus.BAD_REQUEST, "error.time.issueProjectMismatch");
		assertThat(workItems.count()).isZero();
	}

	@Test
	@DisplayName("an issue supplies its own project when none is named")
	void theIssueSuppliesTheProject() {
		WorkItem entry = create(new TimeTrackingService.NewEntry(null, issue.getId(), 30, TODAY,
				null, null, null, null, null, null), owner);

		assertThat(entry.getProjectId()).isEqualTo(project.getId());
	}

	@Test
	@DisplayName("an issue you cannot see is refused")
	void aForeignIssueIsRefused() {
		assertThatThrownBy(() -> create(new TimeTrackingService.NewEntry(null, otherIssue.getId(),
				30, TODAY, null, null, null, null, null, null), outsider))
				.isInstanceOf(ApiException.class);
		assertThat(workItems.count()).isZero();
	}

	// --- the personal list --------------------------------------------------------

	@Test
	@DisplayName("the list is the caller's own entries and nobody else's")
	void theListIsSelfScoped() {
		create(new TimeTrackingService.NewEntry(project.getId(), issue.getId(), 30, TODAY, null,
				"mine", null, null, null, null), owner);
		create(new TimeTrackingService.NewEntry(project.getId(), issue.getId(), 30, TODAY, null,
				"theirs", null, null, null, null), peer);
		// And an unfiled one, which is the strongest case: it belongs to nobody's
		// project, so only the owner rule can be keeping it private.
		create(new TimeTrackingService.NewEntry(null, null, 30, TODAY, null, "private", null, null,
				null, null), owner);

		assertThat(page(owner).getContent()).extracting(WorkItem::getDescription)
				.containsExactlyInAnyOrder("mine", "private");
		assertThat(page(peer).getContent()).extracting(WorkItem::getDescription)
				.containsExactly("theirs");
		// Not even an administrator sees other people's rows here. This list is
		// "my time"; reading somebody else's is what the timesheet is for, with
		// its own rule.
		assertThat(page(admin).getContent()).isEmpty();
	}

	private Page<WorkItem> page(User as) {
		return timeTracking.entries(new TimeTrackingService.EntryFilter(null, null, null, null),
				0, 50, as);
	}

	@Test
	@DisplayName("250 entries page cleanly: three pages, no duplicates, nothing missed")
	void pagingIsStable() {
		for (int i = 0; i < 250; i++) {
			workItems.save(WorkItem.builder().userId(owner.getId()).projectId(project.getId())
					.issueId(issue.getId())
					// Ten a day over 25 days, so every page straddles days and the
					// tiebreaker is what has to settle the order.
					.date(TODAY.minusDays(i / 10)).durationMinutes(30)
					.description("entry-" + i).activityType("Development")
					.startedAt(at(9, 0).plusSeconds((i % 10) * 60L))
					.endedAt(at(10, 0).plusSeconds((i % 10) * 60L))
					.source(WorkItem.Source.APP).tags(List.of()).build());
		}

		Set<String> seen = new java.util.LinkedHashSet<>();
		int pages = 0;
		Page<WorkItem> page;
		do {
			page = timeTracking.entries(
					new TimeTrackingService.EntryFilter(null, null, null, null), pages, 100, owner);
			page.getContent().forEach(item -> seen.add(item.getId()));
			pages++;
		}
		while (page.hasNext());

		assertThat(pages).isEqualTo(3);
		assertThat(seen).as("every entry exactly once").hasSize(250);
		assertThat(page.getTotalElements()).isEqualTo(250);
	}

	@Test
	@DisplayName("the newest day comes first, and within a day the latest start")
	void theOrderIsNewestFirst() {
		create(new TimeTrackingService.NewEntry(null, null, 30, TODAY.minusDays(1), null,
				"yesterday", null, null, null, null), owner);
		create(spanning(null, null, 9, 10), owner);
		create(new TimeTrackingService.NewEntry(null, null, null, null, null, "afternoon",
				at(14, 0), at(15, 0), null, null), owner);
		create(new TimeTrackingService.NewEntry(null, null, 15, TODAY, null, "no clock", null,
				null, null, null), owner);

		assertThat(page(owner).getContent()).extracting(WorkItem::getDescription)
				// Today's three first, latest start first; the one with no start at
				// all sorts last within its day, because it did not happen at a time.
				.containsExactly("afternoon", "work", "no clock", "yesterday");
	}

	@Test
	@DisplayName("a page index far past the end is clamped, not a 500")
	void deepPagingIsClamped() {
		// The offset is page * size and the driver takes a 32-bit skip, so an
		// absurd page index used to overflow it into a negative number and come
		// back as a 500 with a stack trace — one cheap GET each, repeatable.
		assertThat(timeTracking.entries(new TimeTrackingService.EntryFilter(null, null, null, null),
				Integer.MAX_VALUE, 100, owner).getContent()).isEmpty();
	}

	@Test
	@DisplayName("the filters narrow the query, not the page that was already chosen")
	void filtersAreInTheQuery() {
		create(new TimeTrackingService.NewEntry(project.getId(), issue.getId(), 30, TODAY, null,
				"on the project", null, null, null, null), owner);
		create(new TimeTrackingService.NewEntry(null, null, 30, TODAY.minusDays(5), null,
				"long ago and unfiled", null, null, null, null), owner);

		assertThat(filtered(new TimeTrackingService.EntryFilter(null, null, project.getId(), null)))
				.containsExactly("on the project");
		assertThat(filtered(new TimeTrackingService.EntryFilter(TODAY, TODAY, null, null)))
				.containsExactly("on the project");
		assertThat(filtered(new TimeTrackingService.EntryFilter(TODAY.minusDays(6),
				TODAY.minusDays(4), null, null))).containsExactly("long ago and unfiled");
		assertThat(filtered(new TimeTrackingService.EntryFilter(null, null, null, "UNFILED")))
				.as("the text filter is case-insensitive").containsExactly("long ago and unfiled");
		assertThat(filtered(new TimeTrackingService.EntryFilter(null, null, null, "   ")))
				.as("a blank search is no search").hasSize(2);
	}

	private List<String> filtered(TimeTrackingService.EntryFilter filter) {
		return timeTracking.entries(filter, 0, 50, owner).getContent().stream()
				.map(WorkItem::getDescription).toList();
	}

	@Test
	@DisplayName("a search term is text, not a pattern")
	void theSearchIsNotARegularExpression() {
		create(new TimeTrackingService.NewEntry(null, null, 30, TODAY, null, "release 1.0",
				null, null, null, null), owner);

		// ".*" as a pattern would match everything; as a search term it matches
		// nothing here. The same escaping is what keeps a catastrophically
		// backtracking pattern off the database's time.
		assertThat(filtered(new TimeTrackingService.EntryFilter(null, null, null, ".*"))).isEmpty();
		assertThat(filtered(new TimeTrackingService.EntryFilter(null, null, null, "release 1.0")))
				.containsExactly("release 1.0");
		// A literal dot is a literal dot: "1.0" must not match "170".
		create(new TimeTrackingService.NewEntry(null, null, 30, TODAY, null, "build 170",
				null, null, null, null), owner);
		assertThat(filtered(new TimeTrackingService.EntryFilter(null, null, null, "1.0")))
				.containsExactly("release 1.0");
	}

	@Test
	@DisplayName("a descending range is refused rather than silently answered as empty")
	void aBackwardsRangeIsARequestError() {
		// Its own key, not the timesheet's: that one's sentence names a 92-day cap
		// this route does not have.
		assertStatus(() -> timeTracking.entries(new TimeTrackingService.EntryFilter(TODAY,
						TODAY.minusDays(3), null, null), 0, 50, owner),
				HttpStatus.BAD_REQUEST, "error.time.rangeNotAscending");
	}

	// --- the calendar window ---------------------------------------------------------

	private TimeTrackingService.CalendarWindow window(LocalDate from, LocalDate to, User as) {
		return timeTracking.calendar(from, to, as);
	}

	@Test
	@DisplayName("a window holds the caller's own entries, earliest first")
	void theCalendarIsOwnEntriesInOrder() {
		WorkItem later = create(spanning(project.getId(), issue.getId(), 14, 15), owner);
		WorkItem earlier = create(spanning(null, null, 9, 10), owner);
		create(spanning(project.getId(), issue.getId(), 9, 10), peer);

		TimeTrackingService.CalendarWindow window = window(TODAY, TODAY, owner);

		assertThat(window.entries()).extracting(WorkItem::getId)
				.as("the peer's identical hours are not in the owner's calendar")
				.containsExactly(earlier.getId(), later.getId());
		assertThat(window.truncated()).isFalse();
		assertThat(window.from()).isEqualTo(TODAY);
		assertThat(window.to()).isEqualTo(TODAY);
	}

	@Test
	@DisplayName("an entry with no clock is in the window too")
	void theCalendarKeepsDurationOnlyEntries() {
		// It cannot be drawn on the grid, but leaving it out would make a day
		// somebody logged look empty — where to put it is the client's problem.
		WorkItem plain = create(new TimeTrackingService.NewEntry(null, null, 90, TODAY, null,
				"no clock", null, null, null, null), owner);

		assertThat(window(TODAY, TODAY, owner).entries()).extracting(WorkItem::getId)
				.containsExactly(plain.getId());
	}

	@Test
	@DisplayName("the window is bounded at both ends, each with its own message")
	void theWindowHasTwoLimits() {
		assertStatus(() -> window(TODAY, TODAY.minusDays(1), owner), HttpStatus.BAD_REQUEST,
				"error.time.rangeNotAscending");
		// Inclusive: thirty-one days is a window, thirty-two is not.
		assertThat(window(TODAY.minusDays(30), TODAY, owner).entries()).isEmpty();
		assertStatus(() -> window(TODAY.minusDays(31), TODAY, owner), HttpStatus.BAD_REQUEST,
				"error.time.rangeTooLong");
	}

	@Test
	@DisplayName("a year of +999999999 is a bad request, not a stack trace")
	void anAbsurdWindowIsRefusedByCounting() {
		// Both mistakes at once — an impossible date *and* an impossible span —
		// and the answer names the date, which is the one that could not have
		// been meant. The width is still counted rather than offset, or the
		// guard would throw on LocalDate.MAX before reaching either check.
		assertStatus(() -> window(TODAY, LocalDate.MAX, owner), HttpStatus.BAD_REQUEST,
				"error.time.rangeOutOfBounds");
		assertStatus(() -> window(LocalDate.MIN, TODAY, owner), HttpStatus.BAD_REQUEST,
				"error.time.rangeOutOfBounds");
		// An ordinary date with an impossible span is the other message.
		assertStatus(() -> window(TODAY.minusYears(2), TODAY, owner), HttpStatus.BAD_REQUEST,
				"error.time.rangeTooLong");
	}

	@Test
	@DisplayName("a narrow window at an absurd date is refused too")
	void anAbsurdDateIsRefusedEvenInASmallWindow() {
		// The width check cannot see this one: five days at year +999999999 is a
		// perfectly ordinary window. It dies in the driver instead — Spring Data
		// converts a LocalDate through Instant.toEpochMilli, which overflows above
		// about year 292 million — and an unchecked conversion failure is a 500
		// with a stack trace, from one cheap GET that anyone signed in can repeat.
		assertStatus(() -> window(LocalDate.MAX.minusDays(5), LocalDate.MAX, owner),
				HttpStatus.BAD_REQUEST, "error.time.rangeOutOfBounds");
		assertStatus(() -> window(LocalDate.MIN, LocalDate.MIN.plusDays(5), owner),
				HttpStatus.BAD_REQUEST, "error.time.rangeOutOfBounds");
		// And the years either side of the bound behave as stated.
		assertThat(window(LocalDate.of(1970, 1, 1), LocalDate.of(1970, 1, 5), owner).entries())
				.isEmpty();
		assertStatus(() -> window(LocalDate.of(1969, 12, 30), LocalDate.of(1969, 12, 31), owner),
				HttpStatus.BAD_REQUEST, "error.time.rangeOutOfBounds");
	}

	@Test
	@DisplayName("every route that takes a date bounds it, including the frozen one")
	void anAbsurdDateIsRefusedOnEveryRoute() {
		// The 1.x timesheet is the more exposed of them: it is not behind the
		// module's gate, so it answers on instances where none of the routes above
		// exist at all. It keeps its own message — the published app shows that
		// sentence — and gains the bound.
		assertStatus(
				() -> timeTracking.timesheet(LocalDate.MAX.minusDays(5), LocalDate.MAX, null, null,
						owner),
				HttpStatus.BAD_REQUEST, "error.time.invalidRange");

		// The personal list takes an open-ended range, so each end is bounded on
		// its own — one absurd end is enough to reach the driver.
		assertStatus(() -> timeTracking.entries(
				new TimeTrackingService.EntryFilter(LocalDate.MIN, null, null, null), 0, 50, owner),
				HttpStatus.BAD_REQUEST, "error.time.rangeOutOfBounds");
		assertStatus(() -> timeTracking.entries(
				new TimeTrackingService.EntryFilter(null, LocalDate.MAX, null, null), 0, 50, owner),
				HttpStatus.BAD_REQUEST, "error.time.rangeOutOfBounds");

		// An ordinary open-ended range still answers.
		assertThat(timeTracking.entries(
				new TimeTrackingService.EntryFilter(TODAY.minusDays(7), null, null, null), 0, 50,
				owner)).isEmpty();
	}

	@Test
	@DisplayName("a window past the cap is cut and says so")
	void anOverfullWindowIsTruncated() {
		// Inserted straight into the collection: this is about what the read does
		// when a window holds more than it hands out, and writing two thousand
		// entries through the service would only test the service twice.
		List<WorkItem> many = new ArrayList<>();
		for (int i = 0; i < TimeTrackingService.CALENDAR_CAP + 1; i++) {
			many.add(WorkItem.builder().userId(owner.getId()).date(TODAY).durationMinutes(1)
					.activityType("Development").source(WorkItem.Source.APP).build());
		}
		mongo.insert(many, WorkItem.class);

		TimeTrackingService.CalendarWindow window = window(TODAY, TODAY, owner);

		assertThat(window.entries()).hasSize(TimeTrackingService.CALENDAR_CAP);
		assertThat(window.truncated()).isTrue();
	}

	// --- the overlap advice ---------------------------------------------------------

	@Test
	@DisplayName("an overlapping entry is saved and reported, not refused")
	void overlapsAreAdvice() {
		WorkItem morning = create(spanning(project.getId(), issue.getId(), 9, 11), owner);

		WorkItem overlapping = create(spanning(null, null, 10, 12), owner);

		assertThat(workItems.count()).as("saved, not refused").isEqualTo(2);
		assertThat(timeTracking.overlapsOf(overlapping, owner)).containsExactly(morning.getId());
		// And symmetric: the first one now overlaps the second.
		assertThat(timeTracking.overlapsOf(morning, owner)).containsExactly(overlapping.getId());
	}

	@Test
	@DisplayName("back-to-back entries are not an overlap, and neither is another day")
	void whatIsNotAnOverlap() {
		create(spanning(null, null, 9, 10), owner);
		WorkItem next = create(spanning(null, null, 10, 11), owner);
		// Somebody else's identical hours are not this person's overlap.
		create(spanning(project.getId(), issue.getId(), 10, 11), peer);

		assertThat(timeTracking.overlapsOf(next, owner)).isEmpty();
	}

	@Test
	@DisplayName("a lead editing a member's entry is told nothing about their day")
	void overlapsAreNotToldToAForeignEditor() {
		// owner is the project's lead; peer is a member of it. The lead may edit
		// the member's entry, and that must not become a way to read the member's
		// day — the list is keyed on the entry's owner, so without a check it
		// would name every timed entry that person filed, unfiled ones included.
		create(spanning(project.getId(), issue.getId(), 9, 11), peer);
		WorkItem theirs = create(spanning(project.getId(), issue.getId(), 10, 12), peer);
		create(new TimeTrackingService.NewEntry(null, null, null, null, null, "private",
				at(10, 30), at(11, 30), null, null), peer);

		assertThat(timeTracking.overlapsOf(theirs, peer))
				.as("their own day, to them").hasSize(2);
		assertThat(timeTracking.overlapsOf(theirs, owner))
				.as("nothing, to the lead who may edit it").isEmpty();
		assertThat(timeTracking.overlapsOf(theirs, admin))
				.as("nor to an administrator").isEmpty();
	}

	@Test
	@DisplayName("an entry with no clock cannot overlap anything")
	void aDurationOnlyEntryHasNoHours() {
		create(spanning(null, null, 9, 11), owner);
		WorkItem plain = create(new TimeTrackingService.NewEntry(null, null, 60, TODAY, null,
				"no clock", null, null, null, null), owner);

		assertThat(timeTracking.overlapsOf(plain, owner)).isEmpty();
	}

	// --- the policy default ----------------------------------------------------------

	@Test
	@DisplayName("billable comes from the policy when the request does not say")
	void billableFollowsThePolicy() {
		// The shipped default is off, and an entry that does not mention it is
		// stored that way — the flag cannot be reconstructed afterwards, so it has
		// to be decided at write time.
		WorkItem unsaid = create(new TimeTrackingService.NewEntry(null, null, 30, TODAY, null,
				null, null, null, null, null), owner);
		WorkItem said = create(new TimeTrackingService.NewEntry(null, null, 30, TODAY, null, null,
				null, null, null, true), owner);

		assertThat(unsaid.isBillable()).isFalse();
		assertThat(said.isBillable()).isTrue();
	}

	@Test
	@DisplayName("tags are trimmed and de-duplicated on the way in")
	void tagsAreNormalized() {
		WorkItem entry = create(new TimeTrackingService.NewEntry(null, null, 30, TODAY, null, null,
				null, null, List.of(" focus ", "focus", "", "deep work"), null), owner);

		assertThat(entry.getTags()).containsExactly("focus", "deep work");
	}
}
