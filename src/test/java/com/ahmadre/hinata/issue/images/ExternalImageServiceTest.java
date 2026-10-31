package com.ahmadre.hinata.issue.images;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.richtext.LayoutDocs;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The image proxy under load (HIN-181): one fetch per address however many readers ask at once,
 * a few fetches per person and a few more in all, failures remembered a while, and an issue's
 * images authorised once per version of it rather than once per image.
 */
class ExternalImageServiceTest {

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };

	private final IssueService issues = mock(IssueService.class);
	private final MongoTemplate mongo = mock(MongoTemplate.class);
	private final BlockingFetcher fetcher = new BlockingFetcher();
	private final ExternalImageService service = new ExternalImageService(issues, mongo, fetcher);
	private final ExecutorService pool = Executors.newCachedThreadPool();
	private final List<String> sources = new ArrayList<>();
	private Issue issue;

	/** A fetcher that holds every fetch until it is let go, and counts them. */
	private static final class BlockingFetcher extends ExternalImageFetcher {
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicInteger calls = new AtomicInteger();
		volatile RuntimeException failure;

		@Override
		Image fetch(String rawUrl) {
			calls.incrementAndGet();
			try {
				release.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (failure != null) throw failure;
			return new Image(PNG, "image/png");
		}
	}

	@BeforeEach
	void issueWithManyImages() {
		var blocks = new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
		for (int i = 0; i < 40; i++) {
			String src = "https://mail.example.com/img/" + i + ".png";
			sources.add(src);
			blocks.add(LayoutDocs.imageParagraph(src));
		}
		issue = Issue.builder().id("i1").descriptionDoc(LayoutDocs.doc(blocks.toArray(new com.fasterxml.jackson.databind.node.ObjectNode[0])))
				.updatedAt(Instant.parse("2026-10-31T10:00:00Z")).build();
		when(issues.getForUser(eq("i1"), any())).thenReturn(issue);
	}

	@AfterEach
	void stop() {
		fetcher.release.countDown();
		pool.shutdownNow();
	}

	private static User user(String id) {
		return User.builder().id(id).build();
	}

	private Future<ExternalImageService.ExternalImage> async(String src, User user) {
		return pool.submit(() -> service.load("i1", src, user));
	}

	private void awaitCalls(int calls) throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (fetcher.calls.get() < calls && System.nanoTime() < until) Thread.sleep(5);
	}

	@Test
	void manyReadersOfOneImageCauseOneFetch() throws Exception {
		List<Future<ExternalImageService.ExternalImage>> readers = new ArrayList<>();
		for (int i = 0; i < 8; i++) readers.add(async(sources.getFirst(), user("u" + i)));
		awaitCalls(1);
		Thread.sleep(100);

		fetcher.release.countDown();

		for (var reader : readers) assertThat(reader.get(5, TimeUnit.SECONDS).data()).isEqualTo(PNG);
		assertThat(fetcher.calls.get()).isEqualTo(1);
	}

	@Test
	void onePersonHasAtMostFourFetchesRunning() throws Exception {
		User reader = user("u1");
		for (int i = 0; i < 4; i++) async(sources.get(i), reader);
		awaitCalls(4);

		assertThatThrownBy(() -> service.load("i1", sources.get(4), reader))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(429));
	}

	@Test
	void theServerHasAtMostSixteenFetchesRunning() throws Exception {
		for (int i = 0; i < 16; i++) async(sources.get(i), user("u" + i));
		awaitCalls(16);

		assertThatThrownBy(() -> service.load("i1", sources.get(16), user("someone-else")))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(503));
	}

	@Test
	void aFailedFetchIsRememberedAndNotRepeated() {
		fetcher.release.countDown();
		fetcher.failure = ApiException.badRequest("error.media.notAnImage");

		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> service.load("i1", sources.getFirst(), user("u1")))
					.isInstanceOfSatisfying(ApiException.class,
							ex -> assertThat(ex.getMessageKey()).isEqualTo("error.media.notAnImage"));
		}
		assertThat(fetcher.calls.get()).isEqualTo(1);
	}

	@Test
	void anIssuesImagesAreAuthorisedOncePerVersion() {
		fetcher.release.countDown();
		String inComment = "https://mail.example.com/img/reply.png";
		when(mongo.find(any(Query.class), eq(IssueComment.class))).thenReturn(List.of(IssueComment.builder()
				.issueId("i1").textDoc(LayoutDocs.doc(LayoutDocs.imageParagraph(inComment))).build()));

		for (int i = 0; i < 3; i++) service.load("i1", inComment, user("u" + i));
		service.load("i1", sources.getFirst(), user("u1"));
		service.load("i1", sources.get(1), user("u1"));

		verify(mongo, times(1)).find(any(Query.class), eq(IssueComment.class));
	}

	@Test
	void anAddressThatIsNoneOfTheIssuesImagesIsNeverFetched() {
		when(mongo.find(any(Query.class), eq(IssueComment.class))).thenReturn(List.of());

		assertThatThrownBy(() -> service.load("i1", "http://169.254.169.254/", user("u1")))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
		assertThat(fetcher.calls.get()).isZero();
		verify(issues).getForUser(eq("i1"), any());
	}
}
