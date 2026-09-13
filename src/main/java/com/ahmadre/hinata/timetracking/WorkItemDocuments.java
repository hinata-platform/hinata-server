package com.ahmadre.hinata.timetracking;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * Reads a projected document without mapping it onto its entity.
 *
 * <p>A projection that includes three fields cannot be mapped back onto
 * {@code WorkItem} or {@code User}: Spring Data builds them through their
 * all-arguments constructor, and a primitive the projection left out
 * ({@code billable}, {@code active}) arrives as null and fails the instantiation.
 * The readers that only need a handful of values — the retention sweep, the
 * self-hints — therefore query through the entity (so field names and types are
 * mapped as usual) and read the raw document it answers with.
 */
final class WorkItemDocuments {

	private WorkItemDocuments() {
	}

	/** The id as the string the entities carry it as. */
	static String id(Document document) {
		Object id = document.get("_id");
		return id instanceof ObjectId objectId ? objectId.toHexString() : String.valueOf(id);
	}

	/** A calendar day, stored as UTC midnight. */
	static LocalDate day(Document document, String key) {
		Object value = document.get(key);
		return value instanceof Date date ? LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC) : null;
	}

	static Instant instant(Document document, String key) {
		Object value = document.get(key);
		return value instanceof Date date ? date.toInstant() : null;
	}

	static int minutes(Document document) {
		Object value = document.get("durationMinutes");
		return value instanceof Number number ? number.intValue() : 0;
	}
}
