package com.ahmadre.hinata.auth.sso;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Gives an identity provider's callback the same answer however often it arrives.
 *
 * <p>Some browsers deliver the provider's redirect to {@code /login/oauth2/code/…} twice. The first
 * arrival takes the pending authorization request and goes off to redeem the provider's code, which
 * takes a moment; the second finds the request gone and fails at once with
 * {@code authorization_request_not_found}. On iOS that failure is the first link Safari hands to the
 * app — "sign-in failed" — and the success, arriving a moment later, cannot open the app until
 * Safari is in front again. The person sees an error, and is signed in after switching apps.
 *
 * <p>So the second arrival waits for the first one's answer and gives it again: the same app link
 * with the same handoff code, or the same error. Only an exact duplicate qualifies — the same state
 * <em>and</em> the same provider code, compared as a hash — and only within {@link #WINDOW} of the
 * first. That grants nothing the callback URL did not already grant: whoever holds it could have
 * sent it first. The handoff code stays single-use, so the app redeems it once however many times it
 * is delivered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoCallbackReplay {

	/**
	 * How long after the first arrival a duplicate is still answered like it. Seconds, because that
	 * is how far apart two deliveries of one callback are; a minute is long enough for a URL out of
	 * a proxy log to be worth replaying.
	 */
	static final Duration WINDOW = Duration.ofSeconds(20);

	/**
	 * How long a duplicate waits for the first arrival to have its answer. Short on purpose: the
	 * wait holds a request thread, and a callback endpoint is reachable by anybody. Longer than the
	 * round trip to a provider's token endpoint takes, and no longer.
	 */
	static final Duration WAIT = Duration.ofSeconds(3);

	private static final Duration POLL = Duration.ofMillis(150);

	private static final String NOT_FOUND = "authorization_request_not_found";

	private final MongoTemplate mongo;
	private final Clock clock;

	/**
	 * The hash that names one callback: its state and the provider's authorization code. Null when
	 * either is missing — there is then nothing to recognise a duplicate by.
	 */
	static String keyOf(HttpServletRequest request) {
		String state = request.getParameter(OAuth2ParameterNames.STATE);
		String code = request.getParameter(OAuth2ParameterNames.CODE);
		if (!StringUtils.hasText(state) || !StringUtils.hasText(code)) {
			return null;
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest((state + "\n" + code).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is part of every JRE", impossible);
		}
	}

	/** Keeps [target] as the answer to the callback in [request], for a duplicate to be given. */
	public void record(HttpServletRequest request, String target) {
		String state = request.getParameter(OAuth2ParameterNames.STATE);
		String key = keyOf(request);
		if (key == null) {
			return;
		}
		mongo.updateFirst(
				Query.query(Criteria.where("_id").is(state).and("callbackKey").is(key)
						.and("replayTarget").isNull()),
				new Update().set("replayTarget", target),
				PendingAuthorizationRequest.class);
	}

	/**
	 * The answer the first arrival of this callback gave, when [failure] says this is a second one
	 * and the first has answered within {@link #WAIT}; empty otherwise.
	 */
	public Optional<String> replayFor(HttpServletRequest request, AuthenticationException failure) {
		if (!(failure instanceof OAuth2AuthenticationException oauth)
				|| !NOT_FOUND.equals(oauth.getError().getErrorCode())) {
			return Optional.empty();
		}
		String state = request.getParameter(OAuth2ParameterNames.STATE);
		String key = keyOf(request);
		if (key == null) {
			return Optional.empty();
		}
		Instant deadline = clock.instant().plus(WAIT);
		while (true) {
			PendingAuthorizationRequest first = mongo.findById(state, PendingAuthorizationRequest.class);
			if (first == null || first.getConsumedAt() == null || first.getCallbackKey() == null
					|| !MessageDigest.isEqual(
							first.getCallbackKey().getBytes(StandardCharsets.US_ASCII),
							key.getBytes(StandardCharsets.US_ASCII))
					|| first.getConsumedAt().isBefore(clock.instant().minus(WINDOW))) {
				return Optional.empty();
			}
			if (first.getReplayedAt() != null) {
				// Somebody already got this answer. It carried a single-use handoff code, so there
				// is nothing left here to give — and nothing to wait for either.
				return Optional.empty();
			}
			if (first.getReplayTarget() != null) {
				// Taken rather than read: the answer goes out once, to whichever duplicate claims
				// it first, and the document keeps no copy of the code afterwards.
				PendingAuthorizationRequest claimed = mongo.findAndModify(
						Query.query(Criteria.where("_id").is(state).and("callbackKey").is(key)
								.and("replayTarget").ne(null)),
						new Update().unset("replayTarget").set("replayedAt", clock.instant()),
						PendingAuthorizationRequest.class);
				if (claimed == null || claimed.getReplayTarget() == null) {
					return Optional.empty();
				}
				log.info("SSO callback arrived twice; answering the second like the first (state={}…)",
						state.length() <= 8 ? state : state.substring(0, 8));
				return Optional.of(claimed.getReplayTarget());
			}
			if (!clock.instant().isBefore(deadline)) {
				return Optional.empty();
			}
			try {
				Thread.sleep(POLL);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
		}
	}
}
