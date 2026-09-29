package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What waits for a notification window (HIN-131), end to end against a real MongoDB and a
 * clock the test moves by hand: written in the night, sent as one mail and one push in the
 * morning, and what was read in the app meanwhile left out.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(HeldNotificationsIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class HeldNotificationsIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	/** Saturday 3 October 2026, 10:00 UTC. */
	static final Instant SATURDAY = Instant.parse("2026-10-03T10:00:00Z");

	/** Monday 5 October 2026, 09:05 UTC. */
	static final Instant MONDAY_MORNING = Instant.parse("2026-10-05T09:05:00Z");

	static final AtomicReference<Instant> NOW = new AtomicReference<>(SATURDAY);

	@TestConfiguration
	static class FrozenClock {
		@Bean
		@Primary
		Clock testClock() {
			return new Clock() {
				@Override
				public ZoneId getZone() {
					return ZoneOffset.UTC;
				}

				@Override
				public Clock withZone(ZoneId zone) {
					return this;
				}

				@Override
				public Instant instant() {
					return NOW.get();
				}
			};
		}
	}

	@MockitoBean
	private MailService mail;
	@MockitoBean
	private PushService push;
	/** Its scheduled sweep would race the ones the tests drive by hand. */
	@MockitoBean
	private HeldNotificationJob job;

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private NotificationService notifications;
	@Autowired
	private HeldNotifications held;
	@Autowired
	private UserRepository users;

	private User person;

	@BeforeEach
	void seed() {
		NOW.set(SATURDAY);
		when(mail.subjectPrefix()).thenReturn("[Hinata] ");
		for (String collection : List.of("users", "notifications")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		NotificationPreferences prefs = NotificationPreferences.defaults();
		prefs.setSchedule(NotificationPreferences.Schedule.CUSTOM);
		prefs.setWeekdays(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
				DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));
		prefs.setFrom("09:00");
		prefs.setUntil("17:00");
		person = users.save(User.builder().email("person@example.org").username("person")
				.displayName("Person").roles(Set.of(Role.MEMBER)).active(true).locale("en")
				.timezone("UTC").notificationPreferences(prefs).build());
	}

	@Test
	void theNightWaitsAndTheMorningGetsOneMailAndOnePush() {
		notifications.notifySecurityAlert(person, "Signed in", "A new sign-in");
		notifications.notifyAddedToTeam(person.getId(), "t1", "Core");
		notifications.notifyAddedToProject(person.getId(), "p1", "Infrastructure");

		// The security alert went out at once; the two invitations wait.
		verify(mail).sendNotification(eq("person@example.org"), anyString(), eq("Signed in"), any(), any(),
				any(), any(), any());
		assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(
				org.springframework.data.mongodb.core.query.Criteria.where("held").is(true)),
				Notification.class)).isEqualTo(2);

		// Still Saturday: nothing is released.
		assertThat(held.sweep(10)).isZero();

		// One of them was read in the app over the weekend.
		Notification read = mongo.findOne(new org.springframework.data.mongodb.core.query.Query(
				org.springframework.data.mongodb.core.query.Criteria.where("held").is(true)
						.and("link").is("/teams/t1")), Notification.class);
		read.setRead(true);
		mongo.save(read);

		NOW.set(MONDAY_MORNING);
		assertThat(held.sweep(10)).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
		verify(mail).sendTemplate(eq("person@example.org"), anyString(), eq("email/held-summary"), model.capture());
		assertThat((List<?>) model.getValue().get("items")).hasSize(1);
		// Invitations reach people by mail only unless they chose otherwise, so no push waited.
		verify(push, never()).sendToUser(eq(person.getId()), anyString(), anyString(), eq("/notifications"));

		// Released: a second sweep sends nothing again.
		assertThat(held.sweep(10)).isZero();
	}
}
