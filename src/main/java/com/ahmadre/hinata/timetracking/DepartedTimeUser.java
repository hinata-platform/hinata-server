package com.ahmadre.hinata.timetracking;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The pseudonym of an account that has been deleted, and when.
 *
 * <p>The retention policy empties the descriptions on a departed person's entries
 * after N months — and "departed" is not a question {@code work_items} can answer
 * cheaply: the entries keep the user id, and the account it named is simply gone.
 * Finding those ids by comparing every distinct id in the collection against the
 * user collection each night would read the whole instance to find a handful. So
 * the module writes the id down at the moment it learns of the deletion, and the
 * nightly sweep reads this list instead.
 *
 * <p>Nothing else is kept: no name, no e-mail. The id is already on every entry
 * the person left behind; this adds only the day it stopped belonging to anyone.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("time_departed_users")
public class DepartedTimeUser {

	/** The deleted account's id — the pseudonym its entries still carry. */
	@Id
	private String id;

	/**
	 * When the account was deleted. Empty for accounts deleted before HIN-89 recorded
	 * deletions: that moment is not known, and the day they were found is not it.
	 */
	private Instant deletedAt;
}
