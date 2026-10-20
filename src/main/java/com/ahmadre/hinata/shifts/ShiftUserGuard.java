package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One document per person that every assignment of theirs writes first.
 *
 * <p>The overlap check reads the person's other assignments and then inserts one; two transactions
 * doing that for the same person at once would each read "nothing overlaps" and both commit. Both
 * writing this document first turns that into a write conflict, so one of them starts again and
 * then sees the other's assignment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("shift_user_guards")
public class ShiftUserGuard {

	/** The user id. */
	@Id
	private String userId;

	private Long seq;

	/**
	 * Makes sure the guard exists before the transaction that writes it, so two first writes of a
	 * person conflict on an update rather than racing an insert that would end the transaction with
	 * a duplicate key.
	 */
	static void ensure(MongoTemplate mongo, String userId) {
		mongo.upsert(Query.query(Criteria.where("_id").is(userId)), new Update().setOnInsert("seq", 0L),
				ShiftUserGuard.class);
	}

	/** Writes the guard inside the running transaction: this person's writes now run one at a time. */
	static void lock(MongoTemplate mongo, String userId) {
		mongo.findAndModify(Query.query(Criteria.where("_id").is(userId)), new Update().inc("seq", 1L),
				ShiftUserGuard.class);
	}
}
