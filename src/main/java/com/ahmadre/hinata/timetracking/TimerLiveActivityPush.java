package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.notification.DeviceToken;
import com.ahmadre.hinata.notification.DeviceTokenRepository;
import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.notification.LiveActivityKeys;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
public class TimerLiveActivityPush {

	/** How long a token outlives the latest moment its activity can still be on screen. */
	static final Duration TOKEN_GRACE = Duration.ofHours(4);

	/**
	 * The least time between two updates to one person's activities. Changes in
	 * between are folded into one: only the newest state is sent when the gap is
	 * over. An end never waits.
	 */
	static final Duration MIN_GAP = Duration.ofSeconds(2);

	/**
	 * A planned send that has not happened by then is taken as lost (the JVM
	 * was busy, a task failed) and planned again, so one person's updates can
	 * never stall behind it.
	 */
	static final Duration STALLED = MIN_GAP.plusSeconds(30);

	/**
	 * Updates (not ends) one person may send per minute, counted per activity
	 * reached: a timer changed every two seconds on a person with many phones
	 * must not spend the gateway's budget of the whole server.
	 */
	static final int MAX_PUSHES_PER_MINUTE = 60;

	/** People with a slot before idle ones are swept. */
	private static final int MAX_TRACKED = 5_000;

	private final DeviceTokenRepository devices;
	private final LiveActivityKeys keys;
	private final GatewayService gateway;
	private final TimeTrackingSettings settings;
	private final MongoTemplate mongo;
	private final Executor executor;
	private final Map<String, Slot> slots = new ConcurrentHashMap<>();

	/** Only waits out a gap and hands the send to {@link #executor}; never sends itself. */
	private final ScheduledExecutorService delay = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "live-activity-delay");
		t.setDaemon(true);
		return t;
	});

	public TimerLiveActivityPush(DeviceTokenRepository devices, LiveActivityKeys keys, GatewayService gateway,
			TimeTrackingSettings settings, MongoTemplate mongo,
			@Qualifier(LiveActivityExecutorConfig.EXECUTOR) Executor executor) {
		this.devices = devices;
		this.keys = keys;
		this.gateway = gateway;
		this.settings = settings;
		this.mongo = mongo;
		this.executor = executor;
	}

	@PreDestroy
	void stop() {
		delay.shutdownNow();
	}

	/**
	 * A change of a timer and when it happened: the state its activities show
	 * after it, or the end once the timer stopped or its time on screen is over.
	 */
	private record Change(LiveActivityContent.State state, Instant at, boolean ends) {

		static Change of(RunningTimer timer, Instant at) {
			LiveActivityContent.State state = timer == null ? null : LiveActivityContent.of(timer);
			return state == null || !state.dismissAt().isAfter(at)
					? new Change(LiveActivityContent.ended(at), at, true)
					: new Change(state, at, false);
		}
	}

	/** What is known about one person's pushes: when the last went out, and what waits for the next. */
	static final class Slot {
		Instant lastSent = Instant.EPOCH;
		Change waiting;
		boolean scheduled;
		Instant scheduledAt = Instant.EPOCH;
		Instant windowStart = Instant.EPOCH;
		int sentInWindow;

		/** Takes [count] pushes from this minute's budget; false when it does not cover them. */
		boolean spend(int count, Instant now) {
			if (!windowStart.plus(BUDGET_WINDOW).isAfter(now)) {
				windowStart = now;
				sentInWindow = 0;
			}
			if (sentInWindow + count > MAX_PUSHES_PER_MINUTE) return false;
			sentInWindow += count;
			return true;
		}

		/** When this minute's budget is whole again. */
		Instant budgetRenews() {
			return windowStart.plus(BUDGET_WINDOW);
		}
	}

	private static final Duration BUDGET_WINDOW = Duration.ofMinutes(1);

	/**
	 * Sends [timer] — or the end, for null — to every registered activity of
	 * [userId]. [at] is the moment of the change, taken on the caller's thread.
	 *
	 * <p>Only records the change and plans its send: the caller (a request
	 * changing a timer) never waits on the gateway, not even when the pool is
	 * full. At most one update per person every {@link #MIN_GAP}: a change
	 * inside the gap waits, and a newer one replaces it, so a burst of taps
	 * costs one push with the state it ended in. The end of a timer goes at once
	 * and drops whatever was waiting.
	 */
	public void onTimer(String userId, RunningTimer timer, Instant at) {
		if (userId == null || !settings.advancedEnabled() || !gateway.registered()) return;
		Change change = Change.of(timer, at);
		if (change.ends()) {
			slots.remove(userId);
			handEnd(userId, change);
			return;
		}
		evictIdle();
		Slot slot = slots.computeIfAbsent(userId, id -> new Slot());
		synchronized (slot) {
			if (slot.waiting == null || !slot.waiting.at().isAfter(at)) {
				slot.waiting = change;
			}
		}
		plan(userId, slot, Instant.now());
	}

	/**
	 * Plans the send of what waits in [slot]: at once when the gap and the
	 * budget allow, otherwise when they do. Nothing when a send is planned
	 * already, unless that one never ran ({@link #STALLED}).
	 */
	private void plan(String userId, Slot slot, Instant now) {
		Instant due;
		synchronized (slot) {
			if (slot.waiting == null) return;
			if (slot.scheduled && slot.scheduledAt.plus(STALLED).isAfter(now)) return;
			due = slot.lastSent.plus(MIN_GAP);
			slot.scheduled = true;
			slot.scheduledAt = now;
		}
		after(Duration.between(now, due), () -> hand(userId, slot));
	}

	/**
	 * Gives the planned send to the pool. A full pool sends nothing on this
	 * thread: the change stays in the slot and is handed over again a gap later.
	 */
	private void hand(String userId, Slot slot) {
		try {
			executor.execute(() -> flush(userId, slot));
		}
		catch (RejectedExecutionException full) {
			synchronized (slot) {
				slot.scheduledAt = Instant.now();
			}
			after(MIN_GAP, () -> hand(userId, slot));
		}
	}

	/** Gives an end to the pool; when it is full, again a moment later, never on this thread. */
	private void handEnd(String userId, Change change) {
		try {
			executor.execute(() -> sendTo(tokensOf(userId), change));
		}
		catch (RejectedExecutionException full) {
			after(Duration.ofSeconds(1), () -> handEnd(userId, change));
		}
	}

	/**
	 * Sends what waited out the gap, unless an end has taken the slot away
	 * meanwhile. Over the minute's budget the change stays and goes when the
	 * budget renews.
	 */
	private void flush(String userId, Slot slot) {
		List<DeviceToken> tokens = tokensOf(userId);
		Change change;
		synchronized (slot) {
			slot.scheduled = false;
			change = slot.waiting;
			if (change == null || slots.get(userId) != slot) return;
			Instant now = Instant.now();
			if (!slot.spend(tokens.size(), now)) {
				slot.scheduled = true;
				slot.scheduledAt = now;
				after(Duration.between(now, slot.budgetRenews()), () -> hand(userId, slot));
				return;
			}
			slot.waiting = null;
			slot.lastSent = now;
		}
		sendTo(tokens, change);
	}

	private void after(Duration wait, Runnable task) {
		try {
			delay.schedule(task, Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException shuttingDown) {
			// The application is stopping; nothing is sent any more.
		}
	}

	private List<DeviceToken> tokensOf(String userId) {
		return devices.findByUserIdAndKindOrderByCreatedAtAsc(userId, DeviceToken.Kind.LIVE_ACTIVITY_UPDATE);
	}

	private void sendTo(List<DeviceToken> tokens, Change change) {
		for (DeviceToken token : tokens) {
			try {
				send(token, change);
			}
			catch (RuntimeException ex) {
				log.debug("[time] live activity push failed: {}", ex.toString());
			}
		}
	}

	/** Slots nobody waits on say nothing a fresh one would not; dropped once there are many. */
	private void evictIdle() {
		if (slots.size() <= MAX_TRACKED) return;
		Instant quiet = Instant.now().minus(MIN_GAP);
		Instant stalled = Instant.now().minus(STALLED);
		slots.values().removeIf(slot -> {
			synchronized (slot) {
				boolean planned = slot.scheduled && slot.scheduledAt.isAfter(stalled);
				return !planned && slot.waiting == null && slot.lastSent.isBefore(quiet);
			}
		});
	}

	private void send(DeviceToken token, Change change) {
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
		LiveActivityContent.State state = change.state();
		boolean end = change.ends();
		Instant at = change.at();
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
