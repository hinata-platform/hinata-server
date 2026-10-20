package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The calendar feed of one's own shifts (HIN-47), behind {@link ShiftPlanningGate} like every route
 * of the module.
 *
 * <p>The feed itself is the one route of the module without a login: {@code SecurityConfig} lets a
 * {@code GET} on {@code /api/v1/shifts/feed/*.ics} through and nothing else, because a calendar app
 * sends no bearer token. The secret in the address is the key. Everything it can answer besides the
 * calendar carries no body: 404 for anything that is not a live feed, 429 with {@code Retry-After}
 * for a feed fetched again within the minute.
 */
@Tag(name = "Shift planning")
@RestController
@RequiredArgsConstructor
public class ShiftFeedController {

	/** The calendar's media type, with the charset calendar apps expect to be told. */
	static final MediaType CALENDAR = MediaType.parseMediaType("text/calendar; charset=utf-8");

	/** Private: the address is a secret, and a shared cache must not keep what it returned. */
	static final String CACHE = "private, max-age=900";

	private final ShiftFeed feed;
	private final CurrentUser currentUser;

	public record FeedStatus(boolean exists, Instant createdAt, Instant lastAccessedAt) {

		static FeedStatus from(ShiftFeed.Status status) {
			return new FeedStatus(status.exists(), status.createdAt(), status.lastAccessedAt());
		}
	}

	public record FeedCreated(String url, String webcalUrl) {

		static FeedCreated from(ShiftFeed.Created created) {
			return new FeedCreated(created.url(), created.webcalUrl());
		}
	}

	@GetMapping("/api/v1/shifts/feed/{token}.ics")
	public ResponseEntity<byte[]> calendar(@PathVariable String token) {
		return switch (feed.fetch(token)) {
			case ShiftFeed.Calendar calendar -> ResponseEntity.ok()
					.contentType(CALENDAR)
					.header(HttpHeaders.CACHE_CONTROL, CACHE)
					.body(calendar.body());
			case ShiftFeed.Limited limited -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.header(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()))
					.build();
			case ShiftFeed.NotFound notFound -> ResponseEntity.notFound().build();
		};
	}

	@GetMapping("/api/v1/me/shifts/feed")
	public FeedStatus status() {
		return FeedStatus.from(feed.status(currentUser.require()));
	}

	/** A new address, shown this once. An existing one stops working in the same step. */
	@PostMapping("/api/v1/me/shifts/feed")
	@ResponseStatus(HttpStatus.CREATED)
	public FeedCreated create() {
		return FeedCreated.from(feed.create(currentUser.require()));
	}

	@DeleteMapping("/api/v1/me/shifts/feed")
	public ResponseEntity<Void> revoke() {
		feed.revoke(currentUser.require());
		return ResponseEntity.noContent().build();
	}
}
