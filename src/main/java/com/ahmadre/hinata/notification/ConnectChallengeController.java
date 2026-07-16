package com.ahmadre.hinata.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Hinata Connect domain-control challenge. The gateway fetches this
 * public, unauthenticated path on the instance's registered origins and
 * compares the nonce before it enables the deep-link web fallback for this
 * server — proof that whoever enrolled the instance actually controls the
 * domain the relay would redirect browsers to.
 */
@RestController
@RequiredArgsConstructor
public class ConnectChallengeController {

	private final GatewayService gateway;

	@GetMapping(value = "/.well-known/hinata-connect-challenge", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> challenge() {
		return gateway.challenge()
				.map(c -> ResponseEntity.ok()
						.contentType(MediaType.TEXT_PLAIN)
						.cacheControl(CacheControl.noStore())
						.body(c))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
