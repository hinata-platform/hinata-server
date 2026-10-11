package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.timetracking.RunningTimerRepository;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.timetracking.WorkItemRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HIN-97 tools over the real {@code /mcp} transport with the extended module and absence
 * management switched on: registered and annotated, scope-gated, and self-scoped in both
 * directions — a token neither sees nor changes a colleague's entry, by tool or by resource.
 * The module switched off is covered beside the 1.x tools in {@link McpSecurityIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true",
		"hinata.time-tracking.absence-management-enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class McpTimeToolsIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ADMIN = "admin";
	private static final String PASSWORD = "hinata-demo-2026";
	private static final List<String> NEW_TOOLS = List.of("start_timer", "stop_timer", "discard_timer",
			"get_timer", "list_my_work_items", "update_work_item", "time_summary", "my_time_off_balance",
			"request_time_off");

	@LocalServerPort
	private int port;

	@Autowired
	private WorkItemRepository workItems;

	@Autowired
	private RunningTimerRepository timers;

	@Autowired
	private UserRepository users;

	@Autowired
	private IssueRepository issues;

	private User admin;

	@BeforeEach
	void setUp() {
		admin = users.findByUsernameIgnoreCase(ADMIN).orElseThrow();
		timers.findByUserId(admin.getId()).ifPresent(timers::delete);
	}

	private McpTestClient client(String... scopes) {
		return McpTestClient.withScopes(port, ADMIN, PASSWORD, scopes);
	}

	private static Map<String, JsonNode> byName(JsonNode tools) {
		Map<String, JsonNode> named = new HashMap<>();
		tools.forEach(tool -> named.put(tool.path("name").asText(), tool));
		return named;
	}

	private static JsonNode body(JsonNode result) {
		try {
			return JSON.readTree(McpTestClient.text(result));
		}
		catch (Exception e) {
			throw new AssertionError("Tool result was not JSON: " + result, e);
		}
	}

	@Test
	@DisplayName("the new tools are registered and annotated, and the four 1.x tools are still there")
	void registeredAndAnnotated() {
		Map<String, JsonNode> tools = byName(client("worklog:read").tools());

		assertThat(tools).containsKeys(NEW_TOOLS.toArray(String[]::new))
				.containsKeys("log_work", "list_work_items", "my_timesheet", "delete_work_item");
		for (String readOnly : List.of("get_timer", "list_my_work_items", "time_summary", "my_time_off_balance")) {
			assertThat(tools.get(readOnly).path("annotations").path("readOnlyHint").asBoolean(false))
					.as(readOnly + " readOnlyHint").isTrue();
		}
		for (String write : List.of("start_timer", "stop_timer", "update_work_item", "request_time_off")) {
			JsonNode annotations = tools.get(write).path("annotations");
			assertThat(annotations.path("readOnlyHint").asBoolean(false)).as(write + " not read-only").isFalse();
			assertThat(annotations.path("destructiveHint").asBoolean(true)).as(write + " not destructive").isFalse();
		}
		assertThat(tools.get("discard_timer").path("annotations").path("destructiveHint").asBoolean(false))
				.as("discard_timer destructive").isTrue();
	}

	@Test
	@DisplayName("a token without worklog:write may read the timer but not start one")
	void timersAreWrites() {
		McpTestClient reader = client("worklog:read");

		JsonNode refused = reader.call("start_timer", "{}");
		assertThat(refused.path("isError").asBoolean(false)).as("start refused").isTrue();
		assertThat(McpTestClient.text(refused)).contains("worklog:write");
		assertThat(timers.findByUserId(admin.getId())).isEmpty();

		JsonNode read = reader.call("get_timer", "{}");
		assertThat(read.path("isError").asBoolean(false)).as("read allowed").isFalse();
		assertThat(body(read).path("running").asBoolean(true)).isFalse();
	}

	@Test
	@DisplayName("a timer starts once, is read back, and stops into one of the caller's entries")
	void aTimerRoundTrip() {
		McpTestClient agent = client("worklog:read", "worklog:write");

		JsonNode started = agent.call("start_timer", "{\"description\":\"Pairing on HIN-97\"}");
		assertThat(started.path("isError").asBoolean(false)).as(McpTestClient.text(started)).isFalse();
		assertThat(body(agent.call("get_timer", "{}")).path("timer").path("description").asText())
				.isEqualTo("Pairing on HIN-97");

		JsonNode twice = agent.call("start_timer", "{}");
		assertThat(twice.path("isError").asBoolean(false)).isTrue();
		assertThat(McpTestClient.text(twice)).contains("A timer is already running")
				.doesNotContain("error.time");

		JsonNode stopped = agent.call("stop_timer", "{}");
		assertThat(stopped.path("isError").asBoolean(false)).as(McpTestClient.text(stopped)).isFalse();
		JsonNode entry = body(stopped);
		assertThat(entry.path("userId").asText()).isEqualTo(admin.getId());
		assertThat(entry.path("durationMinutes").asInt()).isPositive();
		assertThat(workItems.findById(entry.path("id").asText())).isPresent();

		JsonNode again = agent.call("stop_timer", "{}");
		assertThat(again.path("isError").asBoolean(false)).isTrue();
		assertThat(McpTestClient.text(again)).contains("Timer not found");
	}

	@Test
	@DisplayName("a colleague's entry is neither listed, changed nor read — it reads as missing")
	void selfScopedBothWays() {
		McpTestClient agent = client("worklog:read", "worklog:write");
		WorkItem colleagues = workItems.findAll().stream()
				.filter(item -> item.getUserId() != null && !item.getUserId().equals(admin.getId()))
				.findFirst().orElseThrow(() -> new AssertionError("the demo data has a colleague's entry"));

		JsonNode update = agent.call("update_work_item",
				"{\"id\":\"" + colleagues.getId() + "\",\"description\":\"rewritten by an agent\"}");
		assertThat(update.path("isError").asBoolean(false)).isTrue();
		assertThat(McpTestClient.text(update)).contains("Work item not found");
		assertThat(workItems.findById(colleagues.getId()).orElseThrow().getDescription())
				.isEqualTo(colleagues.getDescription());

		JsonNode resource = agent.readResource("hinata://time/entries/" + colleagues.getId());
		assertThat(resource.has("error") || resource.path("result").path("contents").isEmpty())
				.as("refused: " + resource).isTrue();
		if (colleagues.getDescription() != null) {
			assertThat(resource.toString()).doesNotContain(colleagues.getDescription());
		}

		LocalDate from = colleagues.getDate().minusDays(30);
		JsonNode listed = body(agent.call("list_my_work_items", "{\"from\":\"" + from + "\",\"to\":\""
				+ colleagues.getDate().plusDays(30) + "\",\"size\":100}"));
		for (Iterator<JsonNode> it = listed.path("items").elements(); it.hasNext(); ) {
			assertThat(it.next().path("userId").asText()).isEqualTo(admin.getId());
		}
	}

	@Test
	@DisplayName("the summary is the caller's own and never grouped by person")
	void theSummaryIsOwn() {
		McpTestClient agent = client("worklog:read");
		LocalDate today = LocalDate.now();

		JsonNode summary = agent.call("time_summary", "{\"from\":\"" + today.minusDays(90) + "\",\"to\":\""
				+ today + "\",\"groupBy\":\"DAY\"}");
		assertThat(summary.path("isError").asBoolean(false)).as(McpTestClient.text(summary)).isFalse();
		long own = workItems.findAll().stream()
				.filter(item -> admin.getId().equals(item.getUserId()) && item.getDate() != null
						&& !item.getDate().isBefore(today.minusDays(90)) && !item.getDate().isAfter(today))
				.mapToLong(WorkItem::getDurationMinutes).sum();
		assertThat(body(summary).path("totals").path("filedMinutes").asLong()).isEqualTo(own);

		JsonNode byPerson = agent.call("time_summary", "{\"from\":\"" + today.minusDays(7) + "\",\"to\":\""
				+ today + "\",\"groupBy\":\"USER\"}");
		assertThat(byPerson.path("isError").asBoolean(false)).isTrue();
	}

	/** The 1.x shape is a wire contract: the fields stay, new ones only come beside them. */
	@Test
	@DisplayName("log_work answers with every field it answered with before")
	void theOldShapeHolds() {
		McpTestClient agent = client("worklog:read", "worklog:write");
		String issue = issues.findAll().getFirst().getReadableId();

		JsonNode logged = agent.call("log_work", "{\"issueId\":\"" + issue + "\",\"minutes\":15}");
		assertThat(logged.path("isError").asBoolean(false)).as(McpTestClient.text(logged)).isFalse();
		JsonNode entry = body(logged);
		for (String field : List.of("id", "issueId", "projectId", "userId", "date", "durationMinutes",
				"activityType", "description", "createdAt")) {
			assertThat(entry.has(field)).as(field).isTrue();
		}
		assertThat(entry.path("source").asText()).isEqualTo("MCP");
	}

	@Test
	@DisplayName("the absence tools answer for the caller with absence management on")
	void theBalanceIsOwn() {
		JsonNode balance = client("worklog:read").call("my_time_off_balance", "{}");

		assertThat(balance.path("isError").asBoolean(false)).as(McpTestClient.text(balance)).isFalse();
		assertThat(McpTestClient.text(balance)).doesNotContain("\"kind\":\"SICK\"");
	}
}
