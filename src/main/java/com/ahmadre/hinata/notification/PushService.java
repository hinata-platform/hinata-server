package com.ahmadre.hinata.notification;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Sends push to a user's registered devices via Hinata Connect (the central
 * gateway that owns the published app's FCM credentials). Self-hosters need no
 * Firebase setup of their own. Invalid/expired tokens reported by the gateway
 * are pruned so the device collection self-heals.
 */
@Service
@RequiredArgsConstructor
public class PushService {

	private static final Logger log = LoggerFactory.getLogger(PushService.class);

	private final GatewayService gateway;
	private final DeviceTokenRepository devices;

	/**
	 * Fan a notification out to every device the user has registered. Runs
	 * asynchronously so the originating request (assign issue, comment, …) is
	 * never blocked on the network round-trip to the gateway.
	 */
	@Async
	public void sendToUser(String userId, String title, String body, String link) {
		sendToUser(userId, title, body, link, Map.of());
	}

	/**
	 * As above, with extra key/values in the notification's data payload.
	 *
	 * <p>{@code link} is the route the tap follows and has been there from the
	 * start; {@code extra} is what a client needs in order to decide <em>how</em> to
	 * present the thing before the tap — a {@code type}, above all, so a category
	 * does not have to be guessed from the shape of a path. Kept to a handful of
	 * short values: the gateway bounds the map, and anything personal here would be
	 * readable on a lock screen.
	 *
	 * <p><b>Both</b> overloads carry {@code @Async}, and both have to.
	 * {@code @EnableAsync} advises annotated methods through a proxy, so an
	 * annotation on the delegate above does nothing for the overload it calls —
	 * when {@code NotificationService} started calling this one directly, every push
	 * in the product silently became a blocking HTTP round trip per device token on
	 * the request thread. Annotating only this one would have the mirror-image bug:
	 * the delegate's call to it is a self-invocation, which never passes the proxy.
	 * With both annotated, an outside call to either lands on a pool thread and the
	 * self-invocation simply continues on it.
	 */
	@Async
	public void sendToUser(String userId, String title, String body, String link,
			Map<String, String> extra) {
		if (userId == null) return;
		List<DeviceToken> tokens = devices.findByUserId(userId);
		for (DeviceToken device : tokens) {
			GatewayService.PushResult result =
					gateway.push(device.getToken(), title, body, link, extra);
			if (result == GatewayService.PushResult.DEAD) {
				// Token will never deliver again (uninstalled / rotated / wrong sender):
				// drop it so the device collection self-heals and stops failing.
				devices.deleteByToken(device.getToken());
				log.debug("Pruned dead push token for user {}.", device.getUserId());
			}
		}
	}
}
