package com.ahmadre.hinata.common;

import com.ahmadre.hinata.issue.CommentEvents;
import com.ahmadre.hinata.issue.IssueLinkEvents;
import com.ahmadre.hinata.storage.AttachmentEvents;
import com.ahmadre.hinata.user.SessionsRevokedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing a person out everywhere closes all their streams at once (HIN-172): comments, links and
 * attachments, which kept sending file names for half an hour before.
 */
class ViewerStreamsTest {

	private static final SessionsRevokedEvent JONAS_OUT = new SessionsRevokedEvent("jonas");

	@Test
	void theCommentStreamsOfWhoSignedOutCloseAndOthersStayOpen() {
		CommentEvents events = new CommentEvents();
		assertClosesOnlyTheirs(events::subscribe, events::onSessionsRevoked);
	}

	@Test
	void theLinkStreamsOfWhoSignedOutCloseAndOthersStayOpen() {
		IssueLinkEvents events = new IssueLinkEvents();
		assertClosesOnlyTheirs(events::subscribe, events::onSessionsRevoked);
	}

	@Test
	void theAttachmentStreamsOfWhoSignedOutCloseAndOthersStayOpen() {
		AttachmentEvents events = new AttachmentEvents();
		assertClosesOnlyTheirs(events::subscribe, events::onSessionsRevoked);
	}

	private static void assertClosesOnlyTheirs(BiFunction<String, String, SseEmitter> subscribe,
			Consumer<SessionsRevokedEvent> revoked) {
		SseEmitter jonasOnOne = subscribe.apply("issue-1", "jonas");
		SseEmitter jonasOnTwo = subscribe.apply("issue-2", "jonas");
		SseEmitter lena = subscribe.apply("issue-1", "lena");

		revoked.accept(JONAS_OUT);

		// A completed emitter refuses to send; an open one buffers until it is bound.
		assertThatThrownBy(() -> jonasOnOne.send("x")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> jonasOnTwo.send("x")).isInstanceOf(IllegalStateException.class);
		assertThatCode(() -> lena.send("x")).doesNotThrowAnyException();
	}
}
