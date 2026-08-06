package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.mcp.NotificationTools;
import com.ahmadre.hinata.mcp.ScopeGuard;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRedactor;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A notification whose source has since been frozen stops serving its body.
 *
 * <p>{@code NotificationService} persists a verbatim preview of somebody's comment
 * — {@code actor + ": \"" + preview + "\""} — into {@code Notification.body}, and
 * that row is served back from {@code GET /api/v1/notifications} indefinitely. The
 * push and the e-mail carrying the same text left before anybody reported anything
 * and cannot be recalled; this copy can be, and was not.
 *
 * <p>Against a real MongoDB because the issue-side match is a lookup: the registry
 * holds canonical issue ids and a notification link holds the readable key, so
 * resolving one to the other is a query, and whether it narrows correctly is a
 * property of that query.
 *
 * <p>The real message bundle is loaded rather than a stubbed
 * {@code MessageSource}, so the replacement text has to exist in
 * {@code messages.properties} for these to pass — a redaction that renders a raw
 * i18n key in the bell menu is not a redaction anybody would ship.
 */
@Testcontainers(disabledWithoutDocker = true)
class FrozenNotificationBodyMongoTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final String PREVIEW = "Ada: \"the words nobody may read\"";

	private MongoTemplate mongo;

	@AfterEach
	void resetLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@BeforeEach
	void setUp() {
		// The redactor resolves the withheld text against the REQUEST locale, and
		// LocaleContextHolder falls back to the JVM default when nothing set one — so
		// without this the assertions below pass or fail depending on whose machine
		// runs them.
		LocaleContextHolder.setLocale(Locale.ENGLISH);
		mongo = new MongoTemplate(MongoClients.create(MONGO.getReplicaSetUrl()), "freeze-notify");
		mongo.getDb().drop();
		mongo.save(Issue.builder().id("i-frozen").projectId("p-1").readableId("HIN-7")
				.title("Frozen").attachments(new ArrayList<>()).createdAt(Instant.now()).build());
		mongo.save(Issue.builder().id("i-open").projectId("p-1").readableId("HIN-8")
				.title("Open").attachments(new ArrayList<>()).createdAt(Instant.now()).build());
	}

	/** The mention and reply notices are the ones that quote the comment verbatim. */
	@Test
	void aNotificationQuotingAFrozenCommentStopsServingTheQuote() {
		Notification row = notification(PREVIEW, "/issues/HIN-8?comment=c-frozen");

		redactor(FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.COMMENT, "c-frozen")))
				.redact(List.of(row));

		assertThat(row.getBody()).doesNotContain("the words nobody may read");
		assertThat(row.getBody()).isEqualTo(withheldIn(Locale.ENGLISH));
	}

	/**
	 * An assignment notice carries the issue title as its body, and the link carries
	 * only the readable key — so this is the half that needs the id lookup.
	 */
	@Test
	void aNotificationCarryingAFrozenIssuesTitleStopsServingIt() {
		Notification row = notification("Frozen title in a bell entry", "/issues/HIN-7");

		redactor(FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen")))
				.redact(List.of(row));

		assertThat(row.getBody()).doesNotContain("Frozen title in a bell entry");
	}

	/** The title survives: it names the issue key, not the content. */
	@Test
	void theTitleIsLeftAloneSoTheEntryStillSaysSomethingHappened() {
		Notification row = notification(PREVIEW, "/issues/HIN-8?comment=c-frozen");

		redactor(FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.COMMENT, "c-frozen")))
				.redact(List.of(row));

		assertThat(row.getTitle()).isEqualTo("New comment on HIN-8");
	}

	@Test
	void aNotificationAboutSomethingElseIsUntouched() {
		Notification row = notification(PREVIEW, "/issues/HIN-8?comment=c-other");

		redactor(FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.COMMENT, "c-frozen")))
				.redact(List.of(row));

		assertThat(row.getBody()).isEqualTo(PREVIEW);
	}

	@Test
	void withNothingFrozenNoNotificationIsTouched() {
		Notification row = notification(PREVIEW, "/issues/HIN-7?comment=c-frozen");

		redactor(FreezeFixtures.nothingFrozen()).redact(List.of(row));

		assertThat(row.getBody()).isEqualTo(PREVIEW);
	}

	/**
	 * The stored row keeps its text. A freeze can be released, and a redaction
	 * written into the database would not come back.
	 */
	@Test
	void theStoredRowIsNotRewritten() {
		Notification row = notification(PREVIEW, "/issues/HIN-8?comment=c-frozen");
		mongo.save(row);

		redactor(FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.COMMENT, "c-frozen")))
				.redact(List.of(row));

		assertThat(mongo.findById(row.getId(), Notification.class).getBody()).isEqualTo(PREVIEW);
	}

	// --- the second front door -------------------------------------------------

	/**
	 * The MCP inbox tool is redacted too.
	 *
	 * <p>{@code list_my_notifications} reads {@code NotificationRepository} directly
	 * and mapped straight into its own view, so every argument above applied to it
	 * identically and none of the code did. That is the shape this whole work package
	 * keeps finding: a second front door to a guarded read, built by someone who had
	 * no reason to know the guard existed. The REST controller was redacted, this was
	 * not, and the difference was invisible from either file.
	 *
	 * <p>Exercised through the tool rather than by asserting the call, because what
	 * has to be true is that the <em>view</em> the model receives carries no body — a
	 * redaction applied after the mapping would satisfy a {@code verify} and change
	 * nothing at all.
	 */
	@Test
	void theMcpInboxToolWithholdsAFrozenCommentsBodyToo() {
		Notification row = notification(PREVIEW, "/issues/HIN-8?comment=c-frozen");
		mongo.save(row);
		NotificationRepository repository = mock(NotificationRepository.class);
		when(repository.findByUserIdOrderByCreatedAtDesc(eq("u-1"), any()))
				.thenReturn(new PageImpl<>(new ArrayList<>(List.of(row))));
		ScopeGuard scopes = mock(ScopeGuard.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(User.builder().id("u-1").displayName("Ada")
				.active(true).roles(Set.of(Role.MEMBER)).build());

		NotificationTools.InboxView inbox = new NotificationTools(scopes, currentUser, repository,
				redactor(FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.COMMENT, "c-frozen"))))
				.listMyNotifications(0, 25);

		assertThat(inbox.notifications().items())
				.extracting(NotificationTools.NotificationView::body)
				.containsExactly(withheldIn(Locale.ENGLISH));
	}

	/**
	 * The replacement text as the bundle actually defines it, in the locale the test
	 * pinned.
	 *
	 * <p>Read from the bundle rather than written out here, so the assertion checks
	 * that the redactor resolves a real key — a hard-coded English string passes on
	 * an English machine and fails on a German one, which says nothing about the
	 * product either way.
	 */
	private static String withheldIn(Locale locale) {
		ResourceBundleMessageSource messages = bundle();
		return messages.getMessage("notification.bodyWithheld", null, locale);
	}

	private static ResourceBundleMessageSource bundle() {
		ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
		messages.setBasename("messages");
		messages.setDefaultEncoding("UTF-8");
		messages.setFallbackToSystemLocale(false);
		return messages;
	}

	private NotificationRedactor redactor(FrozenContentService frozen) {
		return new NotificationRedactor(mongo, frozen, bundle());
	}

	private static Notification notification(String body, String link) {
		return Notification.builder().id("n-" + Math.abs(link.hashCode())).userId("u-1")
				.type(Notification.Type.ISSUE_COMMENTED).title("New comment on HIN-8")
				.body(body).link(link).createdAt(Instant.now()).build();
	}
}
