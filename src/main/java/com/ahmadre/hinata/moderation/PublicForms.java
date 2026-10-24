package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;

/**
 * What keeps the form for reports without an account from being a spam gun or a way to bury the
 * moderation (HIN-175), without a third party. A token per form, made here, bound to the address
 * the form was fetched from, at least {@link #MIN_AGE} and at most {@link #MAX_AGE} old and good
 * once; and limits counted in the database rather than in one JVM: {@link #PER_ADDRESS_PER_HOUR}
 * per address and {@link #TOTAL_PER_HOUR} in total.
 */
@Component
class PublicForms {

	static final Duration MIN_AGE = Duration.ofSeconds(3);
	static final Duration MAX_AGE = Duration.ofHours(2);
	static final int PER_ADDRESS_PER_HOUR = 10;
	static final int TOTAL_PER_HOUR = 100;

	private static final int NONCE_BYTES = 16;
	private static final int MAC_BYTES = 32;

	private final MongoTemplate mongo;
	private final Clock clock;
	private final byte[] key;
	private final SecureRandom random = new SecureRandom();

	PublicForms(MongoTemplate mongo, Clock clock, HinataProperties properties) {
		this.mongo = mongo;
		this.clock = clock;
		// A key of its own, derived from the server's signing secret: a form token is no session.
		this.key = hmac(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8),
				"hinata:public-form:v1".getBytes(StandardCharsets.UTF_8));
	}

	/** What a token turned out to be when the form came back. */
	enum Check {
		GOOD,
		/** Sent faster than a person fills in a form. */
		TOO_YOUNG,
		/** Unreadable, made for another address, too old, or used before. */
		INVALID
	}

	/** A fresh token for a form fetched from [ip]. */
	String issue(String ip) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		ByteBuffer body = ByteBuffer.allocate(Long.BYTES + NONCE_BYTES);
		body.putLong(clock.millis()).put(nonce);
		byte[] mac = hmac(key, concat(body.array(), ipHash(ip).getBytes(StandardCharsets.UTF_8)));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(concat(body.array(), mac));
	}

	/** Whether [token] is one this server made for [ip], young and old enough; uses nothing up. */
	Check check(String token, String ip) {
		byte[] raw;
		try {
			raw = Base64.getUrlDecoder().decode(token == null ? "" : token);
		}
		catch (IllegalArgumentException notBase64) {
			return Check.INVALID;
		}
		if (raw.length != Long.BYTES + NONCE_BYTES + MAC_BYTES) {
			return Check.INVALID;
		}
		byte[] body = java.util.Arrays.copyOf(raw, Long.BYTES + NONCE_BYTES);
		byte[] mac = java.util.Arrays.copyOfRange(raw, body.length, raw.length);
		if (!MessageDigest.isEqual(mac, hmac(key, concat(body, ipHash(ip).getBytes(StandardCharsets.UTF_8))))) {
			return Check.INVALID;
		}
		Duration age = Duration.between(Instant.ofEpochMilli(ByteBuffer.wrap(body).getLong()), clock.instant());
		if (age.compareTo(MAX_AGE) > 0 || age.isNegative()) {
			return Check.INVALID;
		}
		if (age.compareTo(MIN_AGE) < 0) {
			return Check.TOO_YOUNG;
		}
		return mongo.exists(Query.query(Criteria.where("tokenHash").is(tokenHash(token))), PublicFormToken.class)
				? Check.INVALID : Check.GOOD;
	}

	/**
	 * Whether [ip], or everybody together, sent more forms in the last hour than they may, counted
	 * after the token of this one was used up ({@link #use}): forms sent at the same moment each see
	 * the others, so none passes a limit the others already reached.
	 */
	boolean overLimit(String ip) {
		Instant hourAgo = clock.instant().minus(Duration.ofHours(1));
		long fromAddress = mongo.count(Query.query(Criteria.where("ipHash").is(ipHash(ip)).and("usedAt").gte(hourAgo)),
				PublicFormToken.class);
		if (fromAddress > PER_ADDRESS_PER_HOUR) {
			return true;
		}
		return mongo.count(Query.query(Criteria.where("usedAt").gte(hourAgo)), PublicFormToken.class) > TOTAL_PER_HOUR;
	}

	/**
	 * Counts one more report of [countedEmail] today; false when it sent its [perDay] already. One
	 * atomic step on a counter of its own: two reports at the same moment cannot both take the last
	 * place. Null, no address, is always room.
	 */
	boolean reserveEmail(String countedEmail, int perDay) {
		if (countedEmail == null) {
			return true;
		}
		String id = HexFormat.of().formatHex(hmac(key, ("mail:" + countedEmail).getBytes(StandardCharsets.UTF_8)))
				.substring(0, 32) + ":" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		Query room = Query.query(Criteria.where("_id").is(id).and("count").lt(perDay));
		Update one = new Update().inc("count", 1L).setOnInsert("createdAt", clock.instant());
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				return mongo.findAndModify(room, one, FindAndModifyOptions.options().upsert(true).returnNew(true),
						PublicReportQuota.class) != null;
			}
			catch (DuplicateKeyException full) {
				// Either the counter is full, or another report created it a moment ago: once more,
				// and a full counter answers the same again.
				if (attempt == 1) {
					return false;
				}
			}
		}
		return false;
	}

	/** Uses [token] up; false when it was used a moment ago by another request. */
	boolean use(String token, String ip) {
		try {
			mongo.insert(PublicFormToken.builder().tokenHash(tokenHash(token)).ipHash(ipHash(ip))
					.usedAt(clock.instant()).build());
			return true;
		}
		catch (DuplicateKeyException twice) {
			return false;
		}
	}

	/** [ip] under the form key: the same address hashes the same, and nothing else can tell which it was. */
	String ipHash(String ip) {
		return HexFormat.of().formatHex(hmac(key, ("ip:" + ip).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
	}

	private String tokenHash(String token) {
		return HexFormat.of().formatHex(hmac(key, ("token:" + token).getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] hmac(byte[] key, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] joined = new byte[a.length + b.length];
		System.arraycopy(a, 0, joined, 0, a.length);
		System.arraycopy(b, 0, joined, a.length, b.length);
		return joined;
	}
}
