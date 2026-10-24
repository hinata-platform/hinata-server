package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.storage.UploadKind;
import com.ahmadre.hinata.storage.UploadRefusedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Refused uploads count into the yearly figures by kind (HIN-177), and a failed count stays quiet. */
class UploadRefusalsTest {

	private final ModerationStats stats = mock(ModerationStats.class);
	private final UploadRefusals refusals = new UploadRefusals(stats);

	@Test
	void aRefusalIsCountedByKind() {
		refusals.on(new UploadRefusedEvent(UploadKind.VOICE, "signature"));

		verify(stats).count(StatMetric.UPLOAD_REFUSED, "VOICE");
	}

	/** Counted in the refused request: a database hiccup must not turn the 422 into a 500. */
	@Test
	void aCountThatFailsLeavesTheRefusalAlone() {
		doThrow(new IllegalStateException("mongo down")).when(stats).count(any(), anyString());

		assertThatCode(() -> refusals.on(new UploadRefusedEvent(UploadKind.MEDIA, "type")))
				.doesNotThrowAnyException();
	}
}
