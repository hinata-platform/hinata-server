package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One-time, idempotent backfill that attributes already-ingested e-mail tickets to the
 * account behind their sender address, the same way {@link EmailIngestService} now does
 * at ingest time. Without it only tickets ingested from here on would notify their
 * author, and every ticket already in the mailbox history would stay author-less.
 *
 * <p>Only rows that carry a {@code reporterEmail} (set exclusively at ingest) and have
 * no {@code reporterId} are considered, so a ticket that already has an author — and a
 * user-authored issue, which never has a {@code reporterEmail} — is never touched. A row
 * whose sender resolves to nobody keeps its empty author and simply matches again on the
 * next boot; that is a lookup per distinct unresolved address, not a write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestedReporterBackfill implements ApplicationRunner {

	private static final String ISSUES = "issues";
	private static final String REPORTER_ID = "reporterId";
	private static final String REPORTER_EMAIL = "reporterEmail";

	private final MongoTemplate mongo;
	private final UserService users;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> issues = mongo.getCollection(ISSUES);
		Document filter = new Document("$and", List.of(
				new Document(REPORTER_EMAIL, new Document("$ne", null)),
				new Document("$or", List.of(
						new Document(REPORTER_ID, new Document("$exists", false)),
						new Document(REPORTER_ID, null)))));
		// One lookup per distinct sender, not per ticket: a support mailbox commonly
		// holds many tickets from the same handful of addresses.
		Map<String, Optional<String>> resolved = new HashMap<>();
		int attributed = 0;
		for (Document issue : issues.find(filter)) {
			Optional<String> userId = senderAccount(issue, resolved);
			if (userId.isPresent()) {
				issues.updateOne(new Document("_id", issue.get("_id")),
						new Document("$set", new Document(REPORTER_ID, userId.get())));
				attributed++;
			}
		}
		if (attributed > 0) {
			log.info("IngestedReporterBackfill: attributed {} e-mail-ingested issue(s) to their "
					+ "sender's account", attributed);
		}
	}

	/**
	 * The account behind a row's sender address, memoised per address in
	 * {@code resolved} so a mailbox full of tickets from the same person costs one
	 * lookup. Empty when the row carries no usable address or nobody owns it.
	 */
	private Optional<String> senderAccount(Document issue, Map<String, Optional<String>> resolved) {
		Object raw = issue.get(REPORTER_EMAIL);
		if (!(raw instanceof String email) || email.isBlank()) {
			return Optional.empty();
		}
		return resolved.computeIfAbsent(email.toLowerCase(Locale.ROOT),
				address -> users.findActiveByEmail(address).map(User::getId));
	}
}
