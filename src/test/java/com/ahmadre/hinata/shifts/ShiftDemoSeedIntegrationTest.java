package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.demo.DemoSeeder;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo workspace with shift planning on (HIN-43). The seeder writes raw documents, so each
 * check reads back through the module's own classes: a field spelled differently fails here.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.mongodb.transactions-enabled=true",
		"hinata.shifts.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class ShiftDemoSeedIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private ShiftReads reads;
	@Autowired
	private DemoSeeder seeder;

	@Test
	void bothEventsAreSeededInEveryStateAndTheResetClearsThem() {
		User admin = users.findByUsernameIgnoreCase("admin").orElseThrow();
		List<ShiftEvent> events = mongo.findAll(ShiftEvent.class);
		assertThat(events).hasSize(2);
		List<Shift> shifts = mongo.findAll(Shift.class);
		assertThat(shifts).hasSize(15);
		// Every state the plan draws: short, at need, over need below the limit, at the limit.
		assertThat(shifts).anyMatch(Shift::understaffed);
		assertThat(shifts).anyMatch(shift -> !shift.understaffed() && shift.assigned() == shift.required());
		assertThat(shifts).anyMatch(shift -> shift.assigned() > shift.required() && !shift.full()
				&& shift.getMaxHelpers() != null);
		assertThat(shifts).anyMatch(Shift::full);
		for (Shift shift : shifts) {
			long active = mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())
					.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class);
			assertThat(shift.assigned()).as(shift.getTitle()).isEqualTo((int) active);
		}
		Instant now = Instant.now();
		assertThat(reads.mine(admin, now, now.plus(Duration.ofDays(60)), 0, 25).getContent()).hasSize(2);
		assertThat(reads.event(admin, events.getFirst().getId()).shifts()).isNotEmpty();

		Set<String> before = events.stream().map(ShiftEvent::getId).collect(Collectors.toSet());
		seeder.seed();
		List<ShiftEvent> after = mongo.findAll(ShiftEvent.class);
		assertThat(after).hasSize(2);
		assertThat(after).noneMatch(event -> before.contains(event.getId()));
		assertThat(mongo.count(Query.query(Criteria.where("eventId").in(before)), ShiftAssignment.class)).isZero();
	}
}
