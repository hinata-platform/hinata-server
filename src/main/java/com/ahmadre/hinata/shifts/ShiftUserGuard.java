package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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
}
