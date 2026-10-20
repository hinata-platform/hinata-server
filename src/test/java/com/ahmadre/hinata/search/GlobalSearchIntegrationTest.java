package com.ahmadre.hinata.search;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.billing.BillingRate;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.people.PersonProfile;
import com.ahmadre.hinata.people.PersonProfileService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.shifts.Shift;
import com.ahmadre.hinata.shifts.ShiftEvent;
import com.ahmadre.hinata.space.Space;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.timeoff.TimeOffRequest;
import com.ahmadre.hinata.timeoff.TimeOffType;
import com.ahmadre.hinata.timetracking.ProjectTimeSettings;
import com.ahmadre.hinata.timetracking.TimesheetApproval;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The global search over every feature (HIN-165) against a real replica set: each category finds
 * its seed and links to exactly it, nothing comes from a project the caller does not see or from
 * somebody else's time and absences, a module that is off answers nothing, and every category
 * sends the same number of reads however much data there is. Also the person profile the people
 * hits open.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.mongodb.transactions-enabled=true"
})
@Import(GlobalSearchIntegrationTest.Probes.class)
@Testcontainers(disabledWithoutDocker = true)
class GlobalSearchIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@TestConfiguration
	static class Probes {

		@Bean
		MongoClientSettingsBuilderCustomizer countSearchReads() {
			return builder -> builder.addCommandListener(SearchReadCounter.listener());
		}

		/**
		 * The categories on the calling thread, so the counter sees every read of a search; on
		 * threads of their own while {@link #parallel} is set, for the test of a slow category.
		 */
		@Bean
		@Primary
		SearchExecutor inlineSearchExecutor() {
			Executor threads = Executors.newVirtualThreadPerTaskExecutor();
			return new SearchExecutor(task -> {
				if (parallel) threads.execute(task);
				else task.run();
			});
		}

		/** A team category that takes longer than a search waits, while {@link #slow} is set. */
		@Bean
		SearchSource slowTeams() {
			return new SearchSource() {
				@Override
				public SearchCategory category() {
					return SearchCategory.TEAMS;
				}

				@Override
				public boolean enabled() {
					return slow;
				}

				@Override
				public List<SearchHit> search(SearchContext context, int cap) {
					try {
						Thread.sleep(SearchService.PART_TIMEOUT.plusSeconds(2).toMillis());
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return List.of(SearchHit.builder().category("TEAMS").id("slow").build());
				}

				@Override
				public long count(SearchContext context, int limit) {
					return 0;
				}
			};
		}
	}

	static volatile boolean parallel;
	static volatile boolean slow;

	private static final String NEEDLE = "Kiwi";

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SearchService search;
	@Autowired
	private SettingsService settings;
	@Autowired
	private PersonProfileService profiles;

	private User me;
	private User stranger;
	private User admin;
	private Project mine;
	private Project hidden;
	private LocalDate today;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "issues", "issue_comments", "articles",
				"spaces", "agile_boards", "sprints", "shift_events", "shifts", "work_items", "timesheet_approvals",
				"time_off_types", "time_off_requests", "time_off", "project_time_settings", "billing_rates",
				"server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		modules(true);
		today = LocalDate.now(ZoneOffset.UTC);
		me = user("mia", Role.MEMBER);
		stranger = user("sam", Role.MEMBER);
		admin = user("ada", Role.ADMIN);
		mine = project("KG", NEEDLE + " Garden", me);
		hidden = project("KS", NEEDLE + " Secret", stranger);
		seedFor(mine, me, "visible");
		seedFor(hidden, stranger, "hidden");
	}

	@AfterEach
	void stopCounting() {
		SearchReadCounter.reset();
		parallel = false;
		slow = false;
	}

	@Test
	@DisplayName("every category finds its seed and links straight to it")
	void everyCategoryFindsItsSeedWithADirectLink() {
		Map<String, List<SearchHit>> byCategory = new java.util.LinkedHashMap<>();
		for (SearchCategory category : SearchCategory.values()) {
			byCategory.put(category.name(), hits(me, NEEDLE, category.name()));
		}
		Issue issue = one(Issue.class, mine);
		assertThat(routes(byCategory, "ISSUES")).contains("/issues/" + issue.getId());
		IssueComment comment = mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("issueId").is(issue.getId())),
				IssueComment.class);
		assertThat(routes(byCategory, "COMMENTS"))
				.containsExactly("/issues/" + issue.getId() + "?comment=" + comment.getId());
		assertThat(routes(byCategory, "PROJECTS")).containsExactly("/issues?projectId=" + mine.getId());
		Team team = one(Team.class, me);
		assertThat(routes(byCategory, "TEAMS")).containsExactly("/teams/" + team.getId());
		assertThat(routes(byCategory, "DOCS")).contains("/knowledge/spaces/Kiwi%20visible");
		assertThat(routes(byCategory, "ATTACHMENTS"))
				.containsExactly("/issues/" + issue.getId() + "?attachment=att-visible");
		ShiftEvent event = one(ShiftEvent.class, mine);
		Shift shift = one(Shift.class, mine);
		assertThat(routes(byCategory, "SHIFTS")).containsExactly("/shifts/events/" + event.getId(),
				"/shifts/events/" + event.getId() + "?shift=" + shift.getId());
		WorkItem entry = one(WorkItem.class, mine);
		TimesheetApproval sheet = one(TimesheetApproval.class, mine);
		assertThat(routes(byCategory, "TIME")).containsExactly(
				"/time/calendar?date=" + entry.getDate() + "&entry=" + entry.getId(),
				"/time/timesheet?date=" + sheet.getPeriodStart() + "&project=" + mine.getId());
		TimeOffRequest request = one(TimeOffRequest.class, me);
		TimeOffType type = kiwiType();
		assertThat(routes(byCategory, "ABSENCES")).containsExactly(
				"/time/absences?scope=requests&request=" + request.getId(),
				"/time/absences?scope=requests&type=" + type.getId());
		BillingRate rate = one(BillingRate.class, mine);
		// Budgets first, then rates (BudgetSearch).
		assertThat(routes(byCategory, "BILLING")).containsExactly(
				"/projects/" + mine.getId() + "/settings?section=timeTracking",
				"/time/rates?rate=" + rate.getId());
		SearchHit rateHit = byCategory.get("BILLING").get(1);
		assertThat(rateHit.getRateLevel()).isEqualTo("PROJECT");
		assertThat(rateHit.getRateKind()).isEqualTo("BILLABLE");
		assertThat(rateHit.getType()).isNull();
		assertThat(rateHit.getStatus()).isNull();
		assertThat(byCategory.get("TEAMS").getFirst().getHue()).isEqualTo(team.getColorHue());
		SearchHit project = byCategory.get("PROJECTS").getFirst();
		assertThat(project.getOpenCount() + project.getDoneCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("nothing from a project out of sight, nor anybody else's time or absences")
	void nothingFromOutOfSight() {
		Set<String> ids = new java.util.HashSet<>();
		for (SearchCategory category : SearchCategory.values()) {
			hits(me, NEEDLE, category.name()).forEach(hit -> ids.add(hit.getId()));
			hits(me, "hidden", category.name()).forEach(hit -> ids.add(hit.getId()));
		}
		assertThat(ids).doesNotContain(hidden.getId(), one(Issue.class, hidden).getId(),
				one(ShiftEvent.class, hidden).getId(), one(Shift.class, hidden).getId(),
				one(WorkItem.class, hidden).getId(), one(TimesheetApproval.class, hidden).getId(),
				one(TimeOffRequest.class, stranger).getId(), one(BillingRate.class, hidden).getId(),
				one(Team.class, stranger).getId(), "att-hidden");
		// The stranger's comment and space, by their own words.
		assertThat(hits(me, "hidden", "COMMENTS")).isEmpty();
		assertThat(hits(me, "hidden", "DOCS")).isEmpty();
		// A member who leads nothing sees the budget of their project and no rate.
		User max = user("max", Role.MEMBER);
		mine.getMemberIds().add(max.getId());
		mongo.save(mine);
		assertThat(hits(max, NEEDLE, "BILLING")).extracting(SearchHit::getKind).containsExactly("BUDGET");
	}

	@Test
	@DisplayName("a module that is off answers nothing from its area")
	void modulesThatAreOffAnswerNothing() {
		modules(false);
		assertThat(hits(me, NEEDLE, "SHIFTS")).isEmpty();
		assertThat(hits(me, NEEDLE, "ABSENCES")).isEmpty();
		assertThat(hits(me, NEEDLE, "BILLING")).isEmpty();
		// Recording time has no switch; the timesheets of the extended module go with it.
		assertThat(hits(me, NEEDLE, "TIME")).extracting(SearchHit::getKind).containsExactly("ENTRY");
		assertThat(hits(me, NEEDLE, "TIME").getFirst().getRoute()).startsWith("/timesheet?date=");
		assertThat(search.search(me, NEEDLE, null, false, 2).groups())
				.extracting(SearchResponse.SearchGroup::category)
				.doesNotContain("SHIFTS", "ABSENCES", "BILLING");
	}

	@Test
	@DisplayName("each category sends the same reads however much there is, and caps its hits")
	void readsPerCategoryStayConstant() {
		Map<SearchCategory, Integer> before = new java.util.EnumMap<>(SearchCategory.class);
		// Once through, so caches that fill on first use do not count against the first category.
		for (SearchCategory category : SearchCategory.values()) {
			hits(me, NEEDLE, category.name());
		}
		for (SearchCategory category : SearchCategory.values()) {
			before.put(category, SearchReadCounter.reads(() -> hits(me, NEEDLE, category.name())));
		}
		for (int i = 0; i < 30; i++) {
			seedFor(mine, me, "more" + i);
		}
		for (SearchCategory category : SearchCategory.values()) {
			int after = SearchReadCounter.reads(() -> assertThat(hits(me, NEEDLE, category.name()))
					.hasSizeLessThanOrEqualTo(24));
			assertThat(after).as(category + ": " + SearchReadCounter.commands).isEqualTo(before.get(category));
		}
		// "All" holds at most five per group.
		search.search(me, NEEDLE, null, false, 2).groups()
				.forEach(group -> assertThat(group.items()).hasSizeLessThanOrEqualTo(5));
	}

	@Test
	@DisplayName("every category has a count under the hits' reach, capped at 100")
	void everyCategoryIsCountedUnderTheSameReach() {
		SearchResponse opened = search.search(me, "", null, false, 2);
		assertThat(opened.counts()).containsKeys(java.util.Arrays.stream(SearchCategory.values())
				.map(Enum::name).toArray(String[]::new));
		// Only what is the caller's or in sight: one each of the visible seed, nothing of the hidden one.
		assertThat(opened.counts()).containsEntry("COMMENTS", 1L).containsEntry("ATTACHMENTS", 1L)
				.containsEntry("TEAMS", 1L).containsEntry("SHIFTS", 2L).containsEntry("TIME", 2L)
				.containsEntry("BILLING", 2L).containsEntry("DOCS", 2L);
		assertThat(opened.cappedCounts()).isEmpty();
		// Nothing is counted while something is typed.
		assertThat(search.search(me, NEEDLE, null, false, 2).counts()).isEmpty();

		for (int i = 0; i < 120; i++) {
			mongo.save(WorkItem.builder().userId(me.getId()).projectId(mine.getId()).date(today.minusDays(i))
					.durationMinutes(15).description("Pruning " + i).build());
		}
		SearchResponse full = search.search(me, "", null, false, 2);
		assertThat(full.counts()).containsEntry("TIME", 100L);
		assertThat(full.cappedCounts()).containsExactly("TIME");
		// A module that is off is not counted either.
		modules(false);
		assertThat(search.search(me, "", null, false, 2).counts()).doesNotContainKeys("SHIFTS", "ABSENCES", "BILLING");
	}

	@Test
	@DisplayName("counting sends the same reads however much there is")
	void countsSendConstantReads() {
		search.search(me, "", null, false, 2);
		int before = SearchReadCounter.reads(() -> search.search(me, "", null, false, 2));
		for (int i = 0; i < 30; i++) {
			seedFor(mine, me, "more" + i);
		}
		int after = SearchReadCounter.reads(() -> search.search(me, "", null, false, 2));
		assertThat(after).as(SearchReadCounter.commands.toString()).isEqualTo(before);
	}

	@Test
	@DisplayName("person, team and default rates are found only by an organisation administrator")
	void organisationRatesOnlyForOrganisationAdmins() {
		User olga = user("olga", Role.ORG_ADMIN);
		Team team = one(Team.class, me);
		BillingRate person = mongo.save(BillingRate.builder().kind(BillingRate.Kind.COST).scope(BillingRate.Scope.USER)
				.scopeId(stranger.getId()).amountCents(5000).currency("EUR").validFrom(today).build());
		BillingRate ofTeam = mongo.save(BillingRate.builder().kind(BillingRate.Kind.BILLABLE)
				.scope(BillingRate.Scope.TEAM).scopeId(team.getId()).amountCents(7000).currency("EUR")
				.validFrom(today).build());
		BillingRate fallback = mongo.save(BillingRate.builder().kind(BillingRate.Kind.BILLABLE)
				.scope(BillingRate.Scope.DEFAULT).amountCents(6000).currency("EUR").validFrom(today).build());

		assertThat(hits(olga, "sam", "BILLING")).extracting(SearchHit::getId, SearchHit::getRoute, SearchHit::getTitle)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(person.getId(), "/time/rates?rate=" + person.getId(), "sam"));
		assertThat(hits(olga, team.getName(), "BILLING")).extracting(SearchHit::getId).containsExactly(ofTeam.getId());
		assertThat(hits(olga, "Default rate", "BILLING")).extracting(SearchHit::getId).containsExactly(fallback.getId());
		olga.setLocale("de");
		mongo.save(olga);
		assertThat(hits(olga, "Standardsatz", "BILLING")).extracting(SearchHit::getId).containsExactly(fallback.getId());
		// A lead reaches none of them, neither as hits nor in the count.
		assertThat(hits(me, "sam", "BILLING")).isEmpty();
		assertThat(hits(me, "Default rate", "BILLING")).isEmpty();
		assertThat(search.search(me, "", null, false, 2).counts()).containsEntry("BILLING", 2L);
		assertThat(search.search(olga, "", null, false, 2).counts().get("BILLING")).isGreaterThanOrEqualTo(3L);
	}

	@Test
	@DisplayName("counts only when the palette opens: no text, every category, not the archive")
	void countsOnlyWhenThePaletteOpens() {
		assertThat(search.search(me, "", null, false, 2).counts()).isNotEmpty();
		assertThat(search.search(me, "", "ISSUES", false, 2).counts()).isEmpty();
		assertThat(search.search(me, "", "ISSUES", false, 2).groups()).isNotEmpty();
		assertThat(search.search(me, "", null, true, 2).counts()).isEmpty();
		assertThat(search.search(me, NEEDLE, null, false, 2).counts()).isEmpty();
	}

	@Test
	@DisplayName("one character asks the five built-in categories only")
	void oneCharacterAsksTheBuiltInCategoriesOnly() {
		assertThat(search.search(me, "k", null, false, 2).groups()).extracting(SearchResponse.SearchGroup::category)
				.isNotEmpty()
				.isSubsetOf("ISSUES", "PROJECTS", "PEOPLE", "BOARDS", "DOCS");
		assertThat(hits(me, "k", "COMMENTS")).isEmpty();
		assertThat(hits(me, "ki", "COMMENTS")).hasSize(1);
	}

	@Test
	@DisplayName("a group says whether there is more than it shows")
	void aGroupSaysWhetherThereIsMore() {
		for (int i = 0; i < 5; i++) {
			seedFor(mine, me, "more" + i);
		}
		Map<String, SearchResponse.SearchGroup> groups = new java.util.HashMap<>();
		search.search(me, NEEDLE, null, false, 2).groups().forEach(group -> groups.put(group.category(), group));
		assertThat(groups.get("ISSUES").items()).hasSize(5);
		assertThat(groups.get("ISSUES").more()).isTrue();
		assertThat(groups.get("TEAMS").items()).hasSize(1);
		assertThat(groups.get("TEAMS").more()).isFalse();
		SearchResponse.SearchGroup scoped = search.search(me, NEEDLE, "ISSUES", false, 2).groups().getFirst();
		assertThat(scoped.items()).hasSize(6);
		assertThat(scoped.more()).isFalse();
	}

	@Test
	@DisplayName("a slow category is left out and the others answer in time")
	void aSlowCategoryIsLeftOut() {
		parallel = true;
		slow = true;
		long started = System.nanoTime();
		SearchResponse response = search.search(me, NEEDLE, null, false, 2);
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		assertThat(took).isLessThan(SearchService.PART_TIMEOUT.plusSeconds(1));
		assertThat(response.groups()).extracting(SearchResponse.SearchGroup::category)
				.contains("ISSUES", "COMMENTS", "TEAMS", "SHIFTS");
		assertThat(response.groups().stream().flatMap(group -> group.items().stream()).map(SearchHit::getId))
				.doesNotContain("slow");
	}

	@Test
	@DisplayName("a small reach is brought to the comments before anything is joined")
	void aSmallReachMatchesCommentsByIssue() {
		Issue foreign = one(Issue.class, hidden);
		List<IssueComment> many = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			many.add(IssueComment.builder().issueId(foreign.getId()).authorId(stranger.getId())
					.type(IssueComment.Type.TEXT).text(NEEDLE + " note " + i).createdAt(Instant.now()).build());
		}
		mongo.insertAll(many);

		SearchReadCounter.reads(() -> assertThat(hits(me, NEEDLE, "COMMENTS")).hasSize(1));
		BsonDocument comments = SearchReadCounter.sent.stream()
				.filter(command -> "issue_comments".equals(command.getString("aggregate", new BsonString("")).getValue()))
				.findFirst().orElseThrow();
		BsonDocument first = comments.getArray("pipeline").get(0).asDocument().getDocument("$match");
		assertThat(first.toJson()).contains("issueId").contains("$in");
		// Only the caller's own comments are read: none of the stranger's 300.
		assertThat(docsExamined(explain(comments))).isLessThanOrEqualTo(5);

		SearchReadCounter.reads(() -> assertThat(search.search(me, "", null, false, 2).counts())
				.containsEntry("COMMENTS", 1L));
		BsonDocument count = SearchReadCounter.sent.stream()
				.filter(command -> "issue_comments".equals(command.getString("aggregate", new BsonString("")).getValue()))
				.findFirst().orElseThrow();
		assertThat(docsExamined(explain(count))).isLessThanOrEqualTo(5);
	}

	@Test
	@DisplayName("a large reach walks a bounded number of comments and joins the reach")
	void aLargeReachWalksABoundedNumberOfComments() {
		List<Issue> many = new ArrayList<>();
		for (int i = 0; i <= CommentSearch.REACH_IDS_MAX; i++) {
			many.add(Issue.builder().projectId(mine.getId()).numberInProject(1000 + i).readableId("KG-" + (1000 + i))
					.title("Bulk " + i).state("Open").updatedAt(Instant.now()).build());
		}
		mongo.insertAll(many);

		SearchReadCounter.reads(() -> assertThat(hits(me, NEEDLE, "COMMENTS")).hasSize(1));
		BsonDocument comments = SearchReadCounter.sent.stream()
				.filter(command -> "issue_comments".equals(command.getString("aggregate", new BsonString("")).getValue()))
				.findFirst().orElseThrow();
		assertThat(comments.getArray("pipeline").get(2).asDocument().getInt32("$limit").getValue())
				.isEqualTo(CommentSearch.SCAN_MAX);
		assertThat(hits(me, "hidden", "COMMENTS")).isEmpty();
		assertThat(search.search(me, "", null, false, 2).counts()).containsEntry("COMMENTS", 1L);
	}

	@Test
	@DisplayName("the attachment search reads only issues that hold a file, on their own index")
	void attachmentsAreReadOnlyFromIssuesWithFiles() {
		for (int i = 0; i < 20; i++) {
			mongo.save(Issue.builder().projectId(mine.getId()).numberInProject(100 + i).readableId("KG-" + (100 + i))
					.title("Plain " + i).state("Open").updatedAt(Instant.now()).build());
		}
		SearchContext context = new SearchContext(me, Map.of(mine.getId(), mine), Set.of(), new Criteria(), NEEDLE,
				Duration.ofSeconds(5));
		Query projects = new AttachmentSearch(mongo).queries(context, 5).getFirst();
		Document plan = mongo.getCollection("issues").find(projects.getQueryObject()).sort(projects.getSortObject())
				.hintString(projects.getHint()).explain(com.mongodb.ExplainVerbosity.EXECUTION_STATS);
		assertThat(plan.toJson()).contains(Issue.WITH_FILES_INDEX);
		// One issue of the caller's project holds a file; the twenty without are never read.
		assertThat(docsExamined(BsonDocument.parse(plan.toJson()))).isEqualTo(1);
		assertThat(search.search(me, "", null, false, 2).counts()).containsEntry("ATTACHMENTS", 1L);
	}

	@Test
	@DisplayName("a money budget the caller may not see is neither found nor counted")
	void aHiddenMoneyBudgetIsNotFound() {
		User max = user("max", Role.MEMBER);
		Project costly = project("CO", NEEDLE + " Costly", me);
		costly.getMemberIds().add(max.getId());
		mongo.save(costly);
		mongo.save(ProjectTimeSettings.builder().projectId(costly.getId()).budgetCents(500_000L)
				.budgetRateKind(ProjectTimeSettings.RateKind.COST).build());

		assertThat(hits(max, "Costly", "BILLING")).isEmpty();
		assertThat(search.search(max, "", null, false, 2).counts()).containsEntry("BILLING", 0L);
		// The lead neither: a cost budget is an administrator's alone. A revenue budget is theirs.
		assertThat(hits(me, "Costly", "BILLING")).isEmpty();
		mongo.updateFirst(Query.query(Criteria.where("projectId").is(costly.getId())),
				new org.springframework.data.mongodb.core.query.Update().set("budgetRateKind", "BILLABLE"),
				ProjectTimeSettings.class);
		assertThat(hits(me, "Costly", "BILLING")).extracting(SearchHit::getKind).containsExactly("BUDGET");
		assertThat(hits(max, "Costly", "BILLING")).isEmpty();
	}

	@Test
	@DisplayName("a space is found by whoever reads a page filed in it")
	void aSpaceIsFoundThroughAReadablePage() {
		mongo.save(Space.builder().name(NEEDLE + " shared").authorId(stranger.getId()).build());
		mongo.save(Article.builder().projectId(mine.getId()).title("Notes").space(NEEDLE + " shared")
				.authorId(stranger.getId()).updatedAt(Instant.now()).build());

		assertThat(hits(me, NEEDLE, "DOCS")).filteredOn(hit -> "SPACE".equals(hit.getKind()))
				.extracting(SearchHit::getTitle).containsExactlyInAnyOrder(NEEDLE + " visible", NEEDLE + " shared");
	}

	@Test
	@DisplayName("a built-in absence type is found by its label in the caller's language")
	void builtInAbsenceTypesMatchTheirLabel() {
		assertThat(hits(me, "Vacation", "ABSENCES")).extracting(SearchHit::getKind, SearchHit::getTitle)
				.contains(org.assertj.core.groups.Tuple.tuple("TYPE", "Vacation"));
		me.setLocale("de");
		mongo.save(me);
		assertThat(hits(me, "Urlaub", "ABSENCES")).extracting(SearchHit::getTitle).contains("Urlaub");
	}

	@Test
	@DisplayName("people link to their profile from revision 2 on, to the user administration before")
	void peopleLinkToTheirProfile() {
		assertThat(search.search(me, "sam", "PEOPLE", false, 2).groups().getFirst().items().getFirst().getRoute())
				.isEqualTo("/people/" + stranger.getId());
		assertThat(search.search(me, "sam", "PEOPLE", false).groups().getFirst().items().getFirst().getRoute())
				.isEqualTo("/admin/users");
	}

	@Test
	@DisplayName("a member sees the shared parts of a profile and no address; an admin sees the address")
	void profileShowsWhatTheViewerMaySee() {
		Project shared = project("SH", "Shared", me);
		shared.getMemberIds().add(stranger.getId());
		mongo.save(shared);
		Team both = mongo.save(Team.builder().key("BOTH").name("Both").members(new ArrayList<>(List.of(
				member(me), member(stranger)))).projectIds(new ArrayList<>()).build());
		mongo.save(TimeOff.builder().userId(stranger.getId()).type(TimeOff.Type.SICK).from(today).to(today).build());

		PersonProfile seen = profiles.profile(me, stranger.getId());
		assertThat(seen.sharedProjects()).extracting(PersonProfile.ProjectRef::id).containsExactly(shared.getId());
		assertThat(seen.sharedTeams()).extracting(PersonProfile.TeamRef::id).containsExactly(both.getId());
		assertThat(seen.email()).isNull();
		assertThat(seen.canAdmin()).isFalse();
		// A colleague who may not know of an absence is not told there is one.
		assertThat(seen.absenceToday()).isNull();
		assertThat(seen.timezone()).isEqualTo("UTC");

		PersonProfile own = profiles.profile(stranger, stranger.getId());
		assertThat(own.self()).isTrue();
		assertThat(own.absenceToday().type()).isEqualTo("SICK");

		PersonProfile asAdmin = profiles.profile(admin, stranger.getId());
		assertThat(asAdmin.email()).isEqualTo("sam@search.test");
		assertThat(asAdmin.canAdmin()).isTrue();
		// The admin shares no project with sam and is not told about any.
		assertThat(asAdmin.sharedProjects()).isEmpty();

		stranger.setActive(false);
		mongo.save(stranger);
		assertThatThrownBy(() -> profiles.profile(me, stranger.getId())).isInstanceOf(ApiException.class);
		assertThat(profiles.profile(admin, stranger.getId()).active()).isFalse();
	}

	// --- seeding ------------------------------------------------------------------------------

	/** One of every searchable thing in [project], owned by [owner], all named after the needle. */
	private void seedFor(Project project, User owner, String word) {
		long number = mongo.count(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(project.getId())), Issue.class) + 1;
		Issue issue = mongo.save(Issue.builder().projectId(project.getId()).numberInProject(number)
				.readableId(project.getKey() + "-" + number).title(NEEDLE + " fence " + word).state("Open")
				.updatedAt(Instant.now())
				.attachments(new ArrayList<>(List.of(Issue.Attachment.builder().id("att-" + word)
						.fileName(NEEDLE + "-plan-" + word + ".pdf").contentType("application/pdf").size(10)
						.uploadedAt(Instant.now()).build())))
				.build());
		mongo.save(IssueComment.builder().issueId(issue.getId()).authorId(owner.getId())
				.type(IssueComment.Type.TEXT).text("The " + NEEDLE + " order is " + word)
				.createdAt(Instant.now()).build());
		if (mongo.count(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("members.userId").is(owner.getId())),
				Team.class) == 0) {
			mongo.save(Team.builder().key("T" + owner.getUsername().toUpperCase()).name(NEEDLE + " team " + word)
					.members(new ArrayList<>(List.of(member(owner)))).projectIds(new ArrayList<>()).build());
			mongo.save(Space.builder().name(NEEDLE + " " + word).authorId(owner.getId()).build());
			mongo.save(Article.builder().projectId(project.getId()).title(NEEDLE + " guide " + word)
					.space(NEEDLE + " " + word).authorId(owner.getId()).updatedAt(Instant.now()).build());
			mongo.save(TimesheetApproval.builder().userId(owner.getId()).projectId(project.getId())
					.periodStart(today.minusDays(7)).periodEnd(today.minusDays(1))
					.status(TimesheetApproval.Status.SUBMITTED).submittedAt(Instant.now()).build());
			mongo.save(ProjectTimeSettings.builder().projectId(project.getId()).budgetMinutes(600).build());
			if (kiwiType() == null) {
				mongo.save(TimeOffType.builder().key("kiwi").name(NEEDLE + " leave").active(true).build());
			}
		}
		Instant start = Instant.now().plus(Duration.ofDays(3));
		ShiftEvent event = mongo.save(ShiftEvent.builder().projectId(project.getId()).title(NEEDLE + " fair " + word)
				.location("Hall").startsAt(start).endsAt(start.plus(Duration.ofHours(8))).timezone("UTC")
				.createdBy(owner.getId()).build());
		mongo.save(Shift.builder().eventId(event.getId()).projectId(project.getId()).title(NEEDLE + " stand " + word)
				.startsAt(start).endsAt(start.plus(Duration.ofHours(2))).build());
		mongo.save(WorkItem.builder().userId(owner.getId()).projectId(project.getId()).date(today)
				.durationMinutes(30).description(NEEDLE + " pruning " + word).build());
		TimeOffType type = kiwiType();
		mongo.save(TimeOffRequest.builder().userId(owner.getId()).typeId(type.getId()).from(today.plusDays(20))
				.to(today.plusDays(21)).status(TimeOffRequest.Status.SUBMITTED).note("harvest " + word).build());
		mongo.save(BillingRate.builder().kind(BillingRate.Kind.BILLABLE).scope(BillingRate.Scope.PROJECT)
				.scopeId(project.getId()).projectId(project.getId()).amountCents(9000).currency("EUR")
				.validFrom(today.minusDays(30)).build());
	}

	private TimeOffType kiwiType() {
		return mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("key").is("kiwi")), TimeOffType.class);
	}

	private void modules(boolean on) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking time = new ServerSettings.TimeTracking();
		time.setAdvancedEnabled(on);
		time.setAbsenceManagementEnabled(on);
		time.setBillingEnabled(on);
		time.setApprovalsEnabled(on);
		current.setTimeTracking(time);
		ServerSettings.Shifts shifts = new ServerSettings.Shifts();
		shifts.setEnabled(on);
		current.setShifts(shifts);
		settings.save(current);
	}

	private User user(String name, Role role) {
		return mongo.save(User.builder().username(name).email(name + "@search.test").displayName(name)
				.roles(new java.util.HashSet<>(Set.of(role))).active(true).timezone("UTC").locale("en").build());
	}

	private Project project(String key, String name, User lead) {
		return mongo.save(Project.builder().key(key).name(name).leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId()))).memberIds(new ArrayList<>(List.of(lead.getId())))
				.build());
	}

	private static TeamMembership member(User user) {
		return TeamMembership.builder().userId(user.getId()).role(TeamRole.MEMBER).access(ProjectAccess.none())
				.build();
	}

	private List<SearchHit> hits(User caller, String q, String scope) {
		return search.search(caller, q, scope, false, 2).groups().stream()
				.flatMap(group -> group.items().stream()).toList();
	}

	/** The plan of an aggregation [command] as sent, with what it read. */
	private BsonDocument explain(BsonDocument command) {
		BsonDocument aggregate = new BsonDocument("aggregate", command.get("aggregate"))
				.append("pipeline", command.get("pipeline")).append("cursor", new BsonDocument());
		Document plan = mongo.getDb().runCommand(new Document("explain", aggregate).append("verbosity", "executionStats"));
		return BsonDocument.parse(plan.toJson());
	}

	/** Every document a plan read, added up over its stages. */
	private static long docsExamined(BsonValue plan) {
		long total = 0;
		if (plan.isDocument()) {
			for (Map.Entry<String, BsonValue> field : plan.asDocument().entrySet()) {
				if (field.getKey().equals("totalDocsExamined") && field.getValue().isNumber()) {
					total += field.getValue().asNumber().longValue();
				} else {
					total += docsExamined(field.getValue());
				}
			}
		} else if (plan.isArray()) {
			for (BsonValue item : plan.asArray()) total += docsExamined(item);
		}
		return total;
	}

	private static List<String> routes(Map<String, List<SearchHit>> byCategory, String category) {
		return byCategory.get(category).stream().map(SearchHit::getRoute).toList();
	}

	/** The first [type] of [owner] — a project or a user, by the field that names it. */
	private <T> T one(Class<T> type, Object owner) {
		String field;
		String id;
		if (owner instanceof Project project) {
			field = "projectId";
			id = project.getId();
		}
		else {
			field = type == Team.class ? "members.userId" : "userId";
			id = ((User) owner).getId();
		}
		org.springframework.data.mongodb.core.query.Query query = org.springframework.data.mongodb.core.query.Query
				.query(org.springframework.data.mongodb.core.query.Criteria.where(field).is(id))
				.with(org.springframework.data.domain.Sort.by("_id"));
		return mongo.findOne(query, type);
	}
}
