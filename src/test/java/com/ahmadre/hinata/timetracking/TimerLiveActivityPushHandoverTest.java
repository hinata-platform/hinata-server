package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.notification.DeviceToken;
import com.ahmadre.hinata.notification.DeviceTokenRepository;
import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.notification.LiveActivityKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A full pool never makes the request that changed the timer send, and loses nothing. */
class TimerLiveActivityPushHandoverTest {

	private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "live-activity-test"));

	@AfterEach
	void stopPool() {
		pool.shutdownNow();
	}

	@Test
	void aRefusedHandoverIsTriedAgainLater_andNeverRunsOnTheCaller() {
		DeviceTokenRepository devices = mock(DeviceTokenRepository.class);
		LiveActivityKeys keys = mock(LiveActivityKeys.class);
		GatewayService gateway = mock(GatewayService.class);
		TimeTrackingSettings settings = mock(TimeTrackingSettings.class);
		when(settings.advancedEnabled()).thenReturn(true);
		when(gateway.registered()).thenReturn(true);
		DeviceToken token = DeviceToken.builder().userId("u").token("aa".repeat(16)).activityId("ref")
				.fcmToken("fcm").kind(DeviceToken.Kind.LIVE_ACTIVITY_UPDATE).build();
		when(devices.findByUserIdAndKindOrderByCreatedAtAsc("u", DeviceToken.Kind.LIVE_ACTIVITY_UPDATE))
				.thenReturn(List.of(token));
		when(keys.open(any())).thenReturn(new byte[32]);
		AtomicReference<String> sentOn = new AtomicReference<>();
		when(gateway.pushLiveActivity(anyString(), anyString(), any(), any(), any(), any(), any()))
				.thenAnswer(call -> {
					sentOn.set(Thread.currentThread().getName());
					return GatewayService.PushResult.SENT;
				});
		AtomicInteger refusals = new AtomicInteger(1);
		TimerLiveActivityPush push = new TimerLiveActivityPush(devices, keys, gateway, settings,
				mock(MongoTemplate.class), task -> {
					if (refusals.getAndDecrement() > 0) throw new RejectedExecutionException("full");
					pool.execute(task);
				});
		try {
			RunningTimer timer = RunningTimer.builder().id("t").userId("u").startedAt(Instant.now())
					.mode(RunningTimer.Mode.COUNTDOWN).plannedMinutes(25).build();

			push.onTimer("u", timer, Instant.now());

			verify(gateway, timeout(TimerLiveActivityPush.MIN_GAP.toMillis() + 3_000))
					.pushLiveActivity(anyString(), anyString(), any(), any(), any(), any(), any());
			assertThat(sentOn.get()).isEqualTo("live-activity-test");
		}
		finally {
			push.stop();
		}
	}

	@Test
	void aLateEndLeavesTheActivityOfTheNextTimerAlone() {
		DeviceTokenRepository devices = mock(DeviceTokenRepository.class);
		LiveActivityKeys keys = mock(LiveActivityKeys.class);
		GatewayService gateway = mock(GatewayService.class);
		TimeTrackingSettings settings = mock(TimeTrackingSettings.class);
		when(settings.advancedEnabled()).thenReturn(true);
		when(gateway.registered()).thenReturn(true);
		Instant stopped = Instant.now();
		DeviceToken old = DeviceToken.builder().userId("u").token("aa".repeat(16)).activityId("old")
				.fcmToken("fcm").kind(DeviceToken.Kind.LIVE_ACTIVITY_UPDATE).createdAt(stopped.minusSeconds(600))
				.build();
		DeviceToken next = DeviceToken.builder().userId("u").token("bb".repeat(16)).activityId("next")
				.fcmToken("fcm").kind(DeviceToken.Kind.LIVE_ACTIVITY_UPDATE).createdAt(stopped.plusSeconds(5))
				.build();
		when(devices.findByUserIdAndKindOrderByCreatedAtAsc("u", DeviceToken.Kind.LIVE_ACTIVITY_UPDATE))
				.thenReturn(List.of(old, next));
		when(keys.open(any())).thenReturn(new byte[32]);
		when(gateway.pushLiveActivity(anyString(), anyString(), any(), any(), any(), any(), any()))
				.thenReturn(GatewayService.PushResult.SENT);
		TimerLiveActivityPush push = new TimerLiveActivityPush(devices, keys, gateway, settings,
				mock(MongoTemplate.class), pool::execute);
		try {
			push.onTimer("u", null, stopped);

			verify(gateway, timeout(2_000)).pushLiveActivity(anyString(),
					org.mockito.ArgumentMatchers.eq("aa".repeat(16)), any(), any(), any(), any(), any());
			verify(gateway, org.mockito.Mockito.after(300).never()).pushLiveActivity(anyString(),
					org.mockito.ArgumentMatchers.eq("bb".repeat(16)), any(), any(), any(), any(), any());
		}
		finally {
			push.stop();
		}
	}
}
