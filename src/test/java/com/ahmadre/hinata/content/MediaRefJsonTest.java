package com.ahmadre.hinata.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a stored object lies stays on the server: a case shows a moderator name, type and size of
 * what was reported, and files come only through the audited evidence route. With the key, an
 * image of a child-safety case could be fetched from the media route, unlogged.
 */
class MediaRefJsonTest {

	@Test
	void theObjectKeyIsNeverWrittenOut() throws Exception {
		String json = new ObjectMapper().writeValueAsString(new MediaRef("media/3f1c", "plan.png", "image/png", 12));

		assertThat(json).doesNotContain("objectKey").doesNotContain("media/3f1c");
		assertThat(json).contains("\"fileName\":\"plan.png\"").contains("\"size\":12");
	}
}
