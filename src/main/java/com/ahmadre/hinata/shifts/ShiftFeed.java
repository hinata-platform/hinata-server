package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.ics.IcsWriter;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A person's shifts as a calendar feed their calendar app subscribes to (HIN-47).
 *
 * <p>The address carries a secret of 32 random bytes and is the only key: calendar apps send no
 * login. Anyone holding it reads the owner's own shifts and nothing else, so it is handled like a
 * password: stored as a SHA-256 only, shown once, replaced or removed by the owner, never logged or
 * audited. Signing out everywhere or resetting the password leaves it alone, because a subscription
 * is not a login; deleting the account deletes it ({@link ShiftErasure}).
 *
 * <p>Every way a fetch can fail looks the same from outside: an unknown, replaced or removed token,
 * an owner who was deactivated or deleted all answer 404, so the address says nothing about why it
 * stopped working. With the module switched off the feed answers 404 too, and the token stays: a
 * subscription picks up again when the module is switched back on.
 *
 * <p>A fetch costs four reads whatever it returns: the token with its owner, the owner's projects
 * and team grants (as {@link ShiftAccess#visibleProjectIds} reads them), and the assignments with
 * their shifts and events in one aggregation. The projects go into it as {@code $in}, so leaving a
 * project takes its shifts out of the next fetch.
 */
@Service
@RequiredArgsConstructor
public class ShiftFeed {

	/** How far back and ahead the feed reaches. */
	static final Duration PAST = Duration.ofDays(30);
	static final Duration AHEAD = Duration.ofDays(180);

	/** Rows per fetch at most; a person with more places in seven months is not a calendar. */
	static final int ROWS_MAX = 1_000;

	/** {@code lastUsedAt} moves at most once in this long, so a feed is not a write per fetch. */
	static final Duration TOUCH_EVERY = Duration.ofHours(1);

	/** What a subscriber is told: fetch again after this long. Matches {@code Cache-Control}. */
	static final Duration REFRESH = Duration.ofHours(1);

	static final String PATH = "/api/v1/shifts/feed/";
	private static final int TOKEN_BYTES = 32;
	/** 32 bytes in Base64-URL without padding. Anything else is not looked up. */
	static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");
	private static final String PRODUCT = "-//Hinata//Shifts//EN";

	private final MongoTemplate mongo;
	private final ShiftAccess access;
	private final ShiftSettings settings;
	private final ShiftTx tx;
	private final ShiftFeedLimiter limiter;
	private final HinataProperties properties;
	private final UserWords words;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	/** The account section's view: whether there is a feed, never its address. */
	public record Status(boolean exists, Instant createdAt, Instant lastUsedAt) {
	}

	/** The address, once, right after it was made. {@code webcalUrl} opens a calendar app. */
	public record Created(String url, String webcalUrl) {
	}

	/** The answer to a fetch. */
	public sealed interface Fetch {
	}

	public record Calendar(byte[] body) implements Fetch {
	}

	public record NotFound() implements Fetch {
	}

	public record Limited(long retryAfterSeconds) implements Fetch {
	}

	// --- the owner's side -----------------------------------------------------

	public Status status(User user) {
		settings.requireEnabled();
		ShiftFeedToken token = mongo.findOne(byUser(user), ShiftFeedToken.class);
		return token == null ? new Status(false, null, null)
				: new Status(true, token.getCreatedAt(), token.getLastUsedAt());
	}

	/**
	 * Makes a new address and ends the old one in the same step: the old hash is gone when the new
	 * one is stored.
	 */
	public Created create(User user) {
		settings.requireEnabled();
		byte[] secret = new byte[TOKEN_BYTES];
		random.nextBytes(secret);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
		tx.run(() -> {
			boolean replaced = mongo.remove(byUser(user), ShiftFeedToken.class).getDeletedCount() > 0;
			mongo.insert(ShiftFeedToken.builder().userId(user.getId()).tokenHash(hash(token))
					.createdAt(clock.instant()).build());
			if (replaced) {
				audit(AuditAction.SHIFT_FEED_TOKEN_REVOKED, user);
			}
			audit(AuditAction.SHIFT_FEED_TOKEN_CREATED, user);
		});
		String url = base() + PATH + token + ".ics";
		return new Created(url, url.replaceFirst("^https?://", "webcal://"));
	}

	/** Ends the address. Nothing there is not an error. */
	public void revoke(User user) {
		settings.requireEnabled();
		tx.run(() -> {
			if (mongo.remove(byUser(user), ShiftFeedToken.class).getDeletedCount() > 0) {
				audit(AuditAction.SHIFT_FEED_TOKEN_REVOKED, user);
			}
		});
	}

	// --- the calendar's side --------------------------------------------------

	/** The feed behind [token]: the calendar, 404 for anything that is not a live feed, or "later". */
	public Fetch fetch(String token) {
		settings.requireEnabled();
		if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
			return new NotFound();
		}
		Document found = owned(hash(token));
		Document owner = found == null ? null : first(found.get("owner"));
		if (owner == null || Boolean.FALSE.equals(owner.get("active"))) {
			return new NotFound();
		}
		String tokenId = String.valueOf(found.get("_id"));
		OptionalLong wait = limiter.consume(tokenId);
		if (wait.isPresent()) {
			return new Limited(wait.getAsLong());
		}
		Instant now = clock.instant();
		touch(tokenId, found.getDate("lastUsedAt"), now);
		String userId = found.getString("userId");
		Locale locale = Locale.forLanguageTag(owner.getString("locale") == null ? "en" : owner.getString("locale"));
		return new Calendar(IcsWriter.bytes(new IcsWriter.Calendar(PRODUCT,
				words.in(locale, "shifts.feed.calendarName"), now, REFRESH, rows(userId, now, locale))));
	}

	/** The token and its owner's state in one read; the owner is looked up by its ObjectId. */
	private Document owned(String tokenHash) {
		List<Document> pipeline = List.of(
				new Document("$match", new Document("tokenHash", tokenHash)),
				new Document("$limit", 1),
				new Document("$addFields", new Document("ownerRef", objectId("$userId"))),
				lookup(mongo.getCollectionName(User.class), "ownerRef", "owner",
						new Document("active", 1).append("locale", 1)));
		return mongo.getCollection(mongo.getCollectionName(ShiftFeedToken.class)).aggregate(pipeline).first();
	}

	/**
	 * The owner's active places from a month back to half a year ahead, in projects they still see,
	 * with the titles of their shifts and events. One aggregation, one batch.
	 */
	private List<IcsWriter.Event> rows(String userId, Instant now, Locale locale) {
		Set<String> projects = access.visibleProjectIds(User.builder().id(userId).build());
		if (projects.isEmpty()) {
			return List.of();
		}
		Instant from = now.minus(PAST);
		Instant to = now.plus(AHEAD);
		Document match = new Document("userId", userId)
				// user_start: userId the equality, startsAt the range (bounded below by the longest shift).
				.append("startsAt", new Document("$gt", Date.from(from.minus(ShiftRules.SHIFT_MAX)))
						.append("$lt", Date.from(to)))
				.append("endsAt", new Document("$gt", Date.from(from)))
				.append("status", new Document("$in", ShiftAssignment.Status.ACTIVE.stream().map(Enum::name).toList()))
				.append("projectId", new Document("$in", new ArrayList<>(projects)));
		List<Document> pipeline = List.of(
				new Document("$match", match),
				new Document("$sort", new Document("startsAt", 1).append("_id", 1)),
				new Document("$limit", ROWS_MAX),
				new Document("$addFields", new Document("shiftRef", objectId("$shiftId"))
						.append("eventRef", objectId("$eventId"))),
				lookup(mongo.getCollectionName(Shift.class), "shiftRef", "shift",
						new Document("title", 1).append("deleted", 1)),
				lookup(mongo.getCollectionName(ShiftEvent.class), "eventRef", "event",
						new Document("title", 1).append("location", 1).append("timezone", 1)));
		String host = host();
		String app = properties.webBase();
		List<IcsWriter.Event> out = new ArrayList<>();
		for (Document row : mongo.getCollection(mongo.getCollectionName(ShiftAssignment.class))
				.aggregate(pipeline).batchSize(ROWS_MAX + 1)) {
			Document shift = first(row.get("shift"));
			Document event = first(row.get("event"));
			if (shift == null || event == null || Boolean.TRUE.equals(shift.get("deleted"))) {
				continue;
			}
			String link = app + ShiftLinks.event(row.getString("eventId"));
			out.add(new IcsWriter.Event(row.get("_id") + "@" + host,
					row.getDate("startsAt").toInstant(), row.getDate("endsAt").toInstant(),
					zone(event.getString("timezone")),
					summary(shift.getString("title"), event.getString("title")),
					event.getString("location"), words.in(locale, "shifts.feed.openInApp", link), link,
					status(row)));
		}
		return out;
	}

	/** "Kasse, Sommerfest": the shift, then its event; either alone when the other has no title. */
	static String summary(String shift, String event) {
		boolean hasShift = shift != null && !shift.isBlank();
		boolean hasEvent = event != null && !event.isBlank();
		if (hasShift && hasEvent) {
			return shift.strip() + ", " + event.strip();
		}
		return hasShift ? shift.strip() : hasEvent ? event.strip() : "";
	}

	private static IcsWriter.Status status(Document row) {
		if (Boolean.TRUE.equals(row.get("cancelled"))) {
			return IcsWriter.Status.CANCELLED;
		}
		return ShiftAssignment.Status.ASSIGNED.name().equals(row.getString("status"))
				? IcsWriter.Status.TENTATIVE : IcsWriter.Status.CONFIRMED;
	}

	/**
	 * Notes that a calendar fetched the feed, at most once an hour: the condition is on the stored
	 * value, so two fetches at once write once.
	 */
	private void touch(String tokenId, Date lastUsedAt, Instant now) {
		Instant due = now.minus(TOUCH_EVERY);
		if (lastUsedAt != null && lastUsedAt.toInstant().isAfter(due)) {
			return;
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(tokenId)
						.orOperator(Criteria.where("lastUsedAt").is(null), Criteria.where("lastUsedAt").lte(due))),
				new Update().set("lastUsedAt", now), ShiftFeedToken.class);
	}

	private void audit(AuditAction action, User user) {
		events.publishEvent(new ShiftDomainEvents.Audited(action, user.getId(), ShiftDomainEvents.label(user),
				user.getId(), Map.of()));
	}

	private static Query byUser(User user) {
		return Query.query(Criteria.where("userId").is(user.getId()));
	}

	/** A stored id as the ObjectId it is stored as, or the string itself when it is not one. */
	private static Document objectId(String field) {
		return new Document("$convert", new Document("input", field).append("to", "objectId")
				.append("onError", field).append("onNull", field));
	}

	private static Document lookup(String from, String localField, String as, Document fields) {
		return new Document("$lookup", new Document("from", from).append("localField", localField)
				.append("foreignField", "_id").append("pipeline", List.of(new Document("$project", fields)))
				.append("as", as));
	}

	private static Document first(Object joined) {
		return joined instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Document document
				? document : null;
	}

	private static ZoneId zone(String id) {
		try {
			return id == null ? ZoneOffset.UTC : ZoneId.of(id);
		}
		catch (DateTimeException invalid) {
			return ZoneOffset.UTC;
		}
	}

	private String base() {
		String url = properties.getBaseUrl();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private String host() {
		try {
			String host = URI.create(properties.getBaseUrl()).getHost();
			return host == null || host.isBlank() ? "hinata" : host;
		}
		catch (IllegalArgumentException malformed) {
			return "hinata";
		}
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
