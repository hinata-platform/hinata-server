package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.notification.DeviceToken;
import com.ahmadre.hinata.notification.DeviceTokenRepository;
import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.notification.LiveActivityKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Moves the Live Activities of a person's iPhones along with their timer
 * (HIN-103): a phase change or a stop on the Mac reaches the lock screen
 * while the app sleeps.
 *
 * <p>Only activities the app started itself and registered for, with the
 * per-device switch on — there is no push-to-start. Each state is sealed under
 * the key of its activity before it leaves ({@link LiveActivityContent}); the
 * gateway and the push services carry ciphertext.
 *
 * <p>Best effort and quiet on purpose: no audit event, no delivery log, no
 * count, no field on the user. A push that does not arrive costs a lock screen
 * that is behind until the app opens, which is what it was before this stage.
 * A token is gone after the end, after a dead answer and after its activity's
 * time plus {@link #TOKEN_GRACE}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimerLiveActivityPush {

	/** How long a token outlives the latest moment its activity can still be on screen. */
	static final Duration TOKEN_GRACE = Duration.ofHours(4);

	private final DeviceTokenRepository devices;
	private final LiveActivityKeys keys;
	private final GatewayService gateway;
	private final TimeTrackingSettings settings;
	private final MongoTemplate mongo;

	/**
	 * Sends [timer] — or the end, for null — to every registered activity of
	 * [userId]. [at] is the moment of the change, taken on the caller's thread.
	 */
	@Async(LiveActivityExecutorConfig.EXECUTOR)
	public void onTimer(String userId, RunningTimer timer, Instant at) {
		if (userId == null || !settings.advancedEnabled() || !gateway.registered()) return;
		List<DeviceToken> tokens = devices.findByUserIdAndKindOrderByCreatedAtAsc(userId,
				DeviceToken.Kind.LIVE_ACTIVITY_UPDATE);
		for (DeviceToken token : tokens) {
			try {
				send(token, timer, at);
			}
			catch (RuntimeException ex) {
				log.debug("[time] live activity push failed: {}", ex.toString());
			}
		}
	}

	private void send(DeviceToken token, RunningTimer timer, Instant at) {
		byte[] key;
		try {
			key = keys.open(token);
		}
		catch (RuntimeException unreadable) {
			// Stored for somebody else, or under a secret that has since changed:
			// the token can never carry a state again.
			devices.deleteByToken(token.getToken());
			return;
		}
		LiveActivityContent.State state = timer == null ? null : LiveActivityContent.of(timer);
		boolean end = state == null || !state.dismissAt().isAfter(at);
		if (end) {
			state = LiveActivityContent.ended(at);
		}
		GatewayService.PushResult result = gateway.pushLiveActivity(token.getFcmToken(), token.getToken(),
				end ? GatewayService.LiveActivityEvent.END : GatewayService.LiveActivityEvent.UPDATE,
				LiveActivityContent.seal(state, key, token.getActivityId()), at,
				end ? null : (state.endsAt() != null ? state.endsAt() : state.dismissAt()),
				end ? at : null);
		if (end || result == GatewayService.PushResult.DEAD) {
			devices.deleteByToken(token.getToken());
		}
		else if (result == GatewayService.PushResult.SENT) {
			mongo.updateFirst(Query.query(Criteria.where("token").is(token.getToken())),
					new Update().set("expiresAt", state.dismissAt().plus(TOKEN_GRACE)), DeviceToken.class);
		}
	}
}
