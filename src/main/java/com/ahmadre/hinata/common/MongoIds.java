package com.ahmadre.hinata.common;

import org.bson.Document;
import org.bson.types.ObjectId;

/** The id of a raw Mongo row as the string the entities use, whichever way the driver hands it over. */
public final class MongoIds {

	private MongoIds() {
	}

	public static String of(Document row) {
		Object id = row.get("_id");
		return id instanceof ObjectId objectId ? objectId.toHexString() : String.valueOf(id);
	}
}
