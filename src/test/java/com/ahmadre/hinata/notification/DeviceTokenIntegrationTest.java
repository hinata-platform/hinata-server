package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.timetracking.RunningTimer;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.timetracking.TimerLiveActivityPush;
import com.ahmadre.hinata.user.UserService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Device tokens since HIN-103: two kinds in one collection, old documents still
 * readable, Live Activity tokens short-lived and out of reach of notifications,
 * and nothing left behind when an account is deleted.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"hinata.time-tracking.advanced-enabled=true",
		"hinata.ics.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class DeviceTokenIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private DeviceTokenRepository devices;
	@Autowired
	private DeviceController controller;
	@Autowired
	private PushService push;
	@Autowired
	private TimerLiveActivityPush liveActivities;
	@Autowired
	private LiveActivityKeys keys;
	@Autowired
	private ApplicationEventPublisher events;

	@MockitoBean
	private CurrentUser currentUser;
	@MockitoBean
	private GatewayService gateway;
	@MockitoSpyBean
	private TimeTrackingSettings timeTracking;

	@BeforeEach
	void reset() {
		mongo.getCollection("device_tokens").deleteMany(new Document());
		when(currentUser.requireId()).thenReturn("user-1");
		when(gateway.registered()).thenReturn(true);
	}

	/** A Live Activity token as APNs hands it out: hex. */
	private static final String ACTIVITY_TOKEN = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

	private static String la(int i) {
		return String.format("bbbb%012x", i);
	}

	private static String key() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

	private void registerActivity(String token, String activityId) {
		controller.register(new DeviceController.RegisterRequest(token, "ios", "liveActivityUpdate",
				activityId, key(), "fcm-of-the-phone"));
	}

	@Test
	void aTokenStoredBeforeKindsExistedLoadsAsAPushToken() {
		// Exactly what a 1.x server wrote: no kind, no expiry, no activity.
		mongo.getCollection("device_tokens").insertOne(new Document("userId", "user-1")
				.append("token", "fcm-old").append("platform", "android")
				.append("createdAt", new Date()).append("lastSeenAt", new Date()));

		DeviceToken old = devices.findByToken("fcm-old").orElseThrow();

		assertThat(old.getKind()).isEqualTo(DeviceToken.Kind.PUSH);
		assertThat(old.isLiveActivity()).isFalse();
		assertThat(devices.findByUserIdAndKindNot("user-1", DeviceToken.Kind.LIVE_ACTIVITY_UPDATE))
				.extracting(DeviceToken::getToken).containsExactly("fcm-old");
	}

	@Test
	void aNotificationNeverReachesALiveActivityToken() {
		controller.register(new DeviceController.RegisterRequest("fcm-phone", "ios", null, null, null, null));
		registerActivity(ACTIVITY_TOKEN, "ref-1");

		push.sendToUser("user-1", "Title", "Body", "/issues/1");

		verify(gateway, timeout(5_000)).push(eq("fcm-phone"), any(), any(), any(), any());
		verify(gateway, never()).push(eq(ACTIVITY_TOKEN), any(), any(), any(), any());
	}

	@Test
	void theContentKeyIsStoredEncryptedAndOpensOnlyForItsOwner() {
		registerActivity(ACTIVITY_TOKEN, "ref-1");

		DeviceToken stored = devices.findByToken(ACTIVITY_TOKEN).orElseThrow();

		assertThat(stored.getKind()).isEqualTo(DeviceToken.Kind.LIVE_ACTIVITY_UPDATE);
		assertThat(stored.getContentKey()).startsWith("v1:");
		assertThat(keys.open(stored)).hasSize(32);
		assertThat(stored.getExpiresAt()).isAfter(Instant.now().plusSeconds(27 * 3600));
		stored.setUserId("user-2");
		assertThatThrownBy(() -> keys.open(stored)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aRegistrationWithoutAUsableKeyIsRefused() {
		assertThatThrownBy(() -> controller.register(new DeviceController.RegisterRequest("t", "ios",
				"liveActivityUpdate", "ref-1", "c2hvcnQ=", "fcm"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> controller.register(new DeviceController.RegisterRequest("t", "ios",
				"liveActivityUpdate", "../../etc", key(), "fcm"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> controller.register(new DeviceController.RegisterRequest("t", "ios",
				"somethingElse", null, null, null))).isInstanceOf(ApiException.class);
		// Not an APNs token: too short, not hex, or far too long.
		for (String token : List.of("abc123", "not-a-token-at-all-zz", "a".repeat(513))) {
			assertThatThrownBy(() -> registerActivity(token, "ref-1")).isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getMessageKey()).isEqualTo("error.device.invalid"));
		}
		assertThat(devices.count()).isZero();
	}

	@Test
	void aLiveActivityIsRefusedWhileTheTimeModuleIsOff() {
		when(timeTracking.advancedEnabled()).thenReturn(false);

		assertThatThrownBy(() -> registerActivity("ab12cd34ef56ab78", "ref-1"))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
		assertThat(devices.count()).isZero();
	}

	@Test
	void aRotatedTokenReplacesTheOldOneAndThePersonKeepsAtMostTwenty() {
		registerActivity(la(901), "ref-same");
		registerActivity(la(902), "ref-same");
		assertThat(devices.findByToken(la(901))).isEmpty();
		assertThat(devices.findByToken(la(902))).isPresent();

		for (int i = 0; i < 25; i++) {
			registerActivity(la(i), "ref-" + i);
		}

		List<DeviceToken> held = devices.findByUserIdAndKindOrderByCreatedAtAsc("user-1",
				DeviceToken.Kind.LIVE_ACTIVITY_UPDATE);
		assertThat(held).hasSize(DeviceController.MAX_LIVE_ACTIVITIES);
		assertThat(held).extracting(DeviceToken::getToken).contains(la(24))
				.doesNotContain(la(0), la(902));
	}

	@Test
	void expiresAtIsATtlIndex() {
		registerActivity(ACTIVITY_TOKEN, "ref-1");

		boolean ttl = false;
		for (Document index : mongo.getCollection("device_tokens").listIndexes()) {
			Document keysOf = index.get("key", Document.class);
			if (keysOf.containsKey("expiresAt")) {
				ttl = index.get("expireAfterSeconds") != null;
			}
		}
		assertThat(ttl).isTrue();
	}

	@Test
	void theEndIsSentSealedAndDeletesTheToken() {
		registerActivity(ACTIVITY_TOKEN, "ref-1");
		when(gateway.pushLiveActivity(anyString(), anyString(), any(), any(), any(), any(), any()))
				.thenReturn(GatewayService.PushResult.SENT);

		liveActivities.onTimer("user-1", null, Instant.now());

		verify(gateway, timeout(5_000)).pushLiveActivity(eq("fcm-of-the-phone"), eq(ACTIVITY_TOKEN),
				eq(GatewayService.LiveActivityEvent.END), any(SealedLiveActivityState.class), any(), eq(null), any());
		awaitGone(ACTIVITY_TOKEN);
	}

	@Test
	void anUpdateKeepsTheTokenAndADeadAnswerDropsIt() {
		registerActivity(ACTIVITY_TOKEN, "ref-1");
		RunningTimer timer = RunningTimer.builder().id("t").userId("user-1").startedAt(Instant.now())
				.mode(RunningTimer.Mode.COUNTDOWN).plannedMinutes(25).description("secret").build();
		when(gateway.pushLiveActivity(anyString(), anyString(), any(), any(), any(), any(), any()))
				.thenReturn(GatewayService.PushResult.SENT);

		liveActivities.onTimer("user-1", timer, Instant.now());

		verify(gateway, timeout(5_000)).pushLiveActivity(eq("fcm-of-the-phone"), eq(ACTIVITY_TOKEN),
				eq(GatewayService.LiveActivityEvent.UPDATE), any(), any(), any(), eq(null));
		assertThat(devices.findByToken(ACTIVITY_TOKEN)).isPresent();

		when(gateway.pushLiveActivity(anyString(), anyString(), any(), any(), any(), any(), any()))
				.thenReturn(GatewayService.PushResult.DEAD);
		liveActivities.onTimer("user-1", timer, Instant.now());
		awaitGone(ACTIVITY_TOKEN);
	}

	@Test
	void deletingTheAccountDeletesEveryTokenOfEveryKind() {
		controller.register(new DeviceController.RegisterRequest("fcm-phone", "ios", null, null, null, null));
		controller.register(new DeviceController.RegisterRequest("https://wns.example/channel", "windows",
				null, null, null, null));
		registerActivity(ACTIVITY_TOKEN, "ref-1");
		when(currentUser.requireId()).thenReturn("user-2");
		controller.register(new DeviceController.RegisterRequest("fcm-other", "android", null, null, null, null));

		events.publishEvent(new UserService.UserDeletedEvent("user-1"));

		assertThat(devices.findByUserId("user-1")).isEmpty();
		assertThat(devices.findByToken("fcm-other")).isPresent();
	}

	@Test
	void unregisteringALiveActivityTokenDeletesItAndItsKey() {
		registerActivity(ACTIVITY_TOKEN, "ref-1");

		controller.unregister(new DeviceController.UnregisterRequest(ACTIVITY_TOKEN));

		assertThat(mongo.getCollection("device_tokens").countDocuments(new Document(Map.of()))).isZero();
	}

	private void awaitGone(String token) {
		long until = System.currentTimeMillis() + 5_000;
		while (devices.findByToken(token).isPresent() && System.currentTimeMillis() < until) {
			Thread.onSpinWait();
		}
		assertThat(devices.findByToken(token)).isEmpty();
	}
}
