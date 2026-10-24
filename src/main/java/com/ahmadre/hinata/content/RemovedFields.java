package com.ahmadre.hinata.content;

import org.springframework.data.mongodb.core.query.Update;

import java.util.Map;

/**
 * The two small steps every surface takes around a removal (HIN-169): it records the fields as
 * they were, and a restore writes them back as they were.
 */
public final class RemovedFields {

	private RemovedFields() {
	}

	/** Records [value] under [name] when there is one: a removal keeps what was there, nothing else. */
	public static void putPresent(Map<String, Object> fields, String name, Object value) {
		if (value != null) {
			fields.put(name, value);
		}
	}

	/** Sets [name] to [value], or takes it away when there is none: a field restored as it was. */
	public static void setOrUnset(Update update, String name, Object value) {
		if (value == null) {
			update.unset(name);
		}
		else {
			update.set(name, value);
		}
	}
}
