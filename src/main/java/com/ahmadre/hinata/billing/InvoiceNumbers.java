package com.ahmadre.hinata.billing;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The gapless invoice number sequence: one counter per prefix and year, advanced with one atomic
 * {@code findAndModify} — the database hands every caller a different number, however many issue
 * at once.
 *
 * <p>Gapless is the caller's half of the bargain: a number is taken only once everything that
 * could still refuse the invoice has been checked, and the write that puts it on the invoice
 * follows directly. See {@code InvoiceService#issue}.
 */
@Component
@RequiredArgsConstructor
class InvoiceNumbers {

	/** One counter. {@code _id} is {@code PREFIX-YEAR}. */
	@Data
	@Document("invoice_sequences")
	static class Sequence {

		@Id
		private String id;

		private long value;
	}

	private final MongoTemplate mongo;

	/** The next number for [prefix] in [year], formatted {@code PREFIX-YEAR-00001}. */
	String next(String prefix, int year) {
		String key = prefix + "-" + year;
		Sequence sequence = mongo.findAndModify(Query.query(Criteria.where("_id").is(key)),
				new Update().inc("value", 1), FindAndModifyOptions.options().upsert(true).returnNew(true),
				Sequence.class);
		long value = sequence == null ? 1 : sequence.getValue();
		return String.format(Locale.ROOT, "%s-%05d", key, value);
	}
}
