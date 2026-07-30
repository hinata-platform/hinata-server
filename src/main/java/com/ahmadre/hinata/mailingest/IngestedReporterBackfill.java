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
import java.util.Map;

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

	private final MongoTemplate mongo;
	private final UserService users;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> col = mongo.getCollection("issues");
		Document filter = new Document("$and", List.of(
				new Document("reporterEmail", new Document("$ne", null)),
				new Document("$or", List.of(
						new Document("reporterId", new Document("$exists", false)),
						new Document("reporterId", null)))));
		// One lookup per distinct sender, not per ticket: a support mailbox commonly
		// holds many tickets from the same handful of addresses.
		Map<String, String> resolved = new HashMap<>();
		int attributed = 0;
		for (Document doc : col.find(filter)) {
			Object raw = doc.get("reporterEmail");
			if (!(raw instanceof String email) || email.isBlank()) {
				continue;
			}
			String userId = resolved.computeIfAbsent(email.toLowerCase(java.util.Locale.ROOT),
					key -> users.findActiveByEmail(key).map(User::getId).orElse(null));
			if (userId == null) {
				continue;
			}
			col.updateOne(new Document("_id", doc.get("_id")),
					new Document("$set", new Document("reporterId", userId)));
			attributed++;
		}
		if (attributed > 0) {
			log.info("IngestedReporterBackfill: attributed {} e-mail-ingested issue(s) to their "
					+ "sender's account", attributed);
		}
	}
}
