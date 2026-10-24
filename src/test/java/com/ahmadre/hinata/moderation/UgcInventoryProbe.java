package com.ahmadre.hinata.moderation;

import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A document with a field nobody reviewed, to show {@link UgcInventoryTest} catches it. Never
 * stored; only read by reflection.
 */
@SuppressWarnings("unused")
@Document("ugc_probe")
class UgcInventoryProbe {

	private String id;
	private String words;
}
