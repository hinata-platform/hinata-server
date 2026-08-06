package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.moderation.freeze.FrozenContent;
import com.ahmadre.hinata.moderation.freeze.FrozenIssues;
import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
import com.ahmadre.hinata.moderation.report.ContentReport;
import com.ahmadre.hinata.setup.ServerSettings;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Transient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on the moderation wiring.
 *
 * <p>Every check here exists because the corresponding mistake is silent. A gate
 * that is bypassed does not throw, does not log and does not fail a test — the
 * product simply stops moderating, and nobody finds out until someone posts
 * something. Behavioural tests cannot catch that, because the code under test
 * looks exactly the same either way; only a check over the source can.
 */
class ModerationWiringTest {

	private static final Path MAIN = Path.of("src/main/java");

	/**
	 * {@code RichTextService} has a no-arg constructor that leaves the gate null,
	 * for the conformance corpus and round-trip tests that convert documents they
	 * never store. In production that constructor would switch moderation off for
	 * every description, comment and article at once — the single worst failure this
	 * feature has — and it would do it without a symptom.
	 */
	@Test
	void noProductionCodeUsesTheUngatedRichTextServiceConstructor() {
		assertThat(sourceContaining("new RichTextService()"))
				.as("production code must let Spring inject the moderating constructor")
				.isEmpty();
	}

	/**
	 * {@code putObject} deliberately skips the content-type allow-list and the
	 * magic-byte check, and is documented as trusting its caller. Three of its four
	 * original callers were carrying user-supplied bytes anyway, the worst being
	 * inbound e-mail: a content type straight out of an attacker-controlled MIME
	 * header, stored with no allow-list and no signature check. That path now goes
	 * through {@link com.ahmadre.hinata.storage.StorageService#putChecked}.
	 *
	 * <p>The remaining set is pinned because each entry is a deliberate exception —
	 * bytes the server produced itself after validating and re-encoding the input.
	 * A new name appearing here is a new unvalidated upload path, and should have to
	 * be argued for in a diff rather than arrived at by accident.
	 */
	@Test
	void onlyKnownCallersUseTheValidationSkippingStorageMethod() {
		assertThat(sourceContaining("storage.putObject("))
				.containsExactlyInAnyOrder(
						// server-re-encoded audio, checked against the voice allow-list
						// and its magic bytes before it gets here
						"issue/IssueService.java",
						// server-compressed JPEG
						"me/AvatarService.java",
						// server-normalised PNG
						"setup/OrganizationLogoService.java");
	}

	/**
	 * Inbound e-mail is the one ingress where the author is not a colleague who
	 * signed in, so it is the one that must not regress to the trusting path.
	 */
	@Test
	void emailAttachmentsGoThroughTheValidatingStoragePath() {
		String source = read(MAIN.resolve("com/ahmadre/hinata/mailingest/EmailIngestService.java"));

		assertThat(source).contains("putChecked");
		assertThat(source).doesNotContain("storage.putObject(");
		assertThat(source).contains("ModerationSurface.EMAIL_ATTACHMENT");
		assertThat(source).contains("ModerationSurface.EMAIL_INGEST");
	}

	/**
	 * The categories that no admin setting may weaken. Named here as well as in
	 * {@link ModerationPolicy} so that adding a constant to the enum and forgetting
	 * the policy — or quietly making one of these overridable — fails.
	 */
	@Test
	void theNonOverridableCategoriesAreExactlyTheTwoIntendedOnes() {
		// isOverridable is a pure lookup over a static set, so the collaborators are
		// genuinely unused here rather than merely unexercised.
		ModerationPolicy policy = new ModerationPolicy(null, null);

		List<ModerationCategory> locked = Stream.of(ModerationCategory.values())
				.filter(category -> !policy.isOverridable(category))
				.toList();

		assertThat(locked).containsExactlyInAnyOrder(
				ModerationCategory.SEXUAL_MINORS, ModerationCategory.MALWARE);
	}

	// --- the admin panel actually reaching enforcement ---------------------------

	/**
	 * Every override on {@link ServerSettings.Moderation} is read by a resolver of
	 * the same name on {@link ModerationPolicy}.
	 *
	 * <p>A nullable field nobody resolves is a switch that does nothing: it saves,
	 * it round-trips through the API, the panel renders it with the value the admin
	 * chose, and not one line of enforcement ever asks. This feature has shipped that
	 * twice, which is why the check is structural rather than a behavioural test per
	 * field — a behavioural test can only be written for a resolver somebody
	 * remembered to write, and the ones that were forgotten had no test to fail.
	 *
	 * <p>Matching on the name is enough here, and deliberately so. It cannot prove
	 * the resolver is any good — {@code ModerationPolicyTest} and
	 * {@code HttpImageModeratorTest} do that — but it is the only thing that fails
	 * when a field is added and nothing is written at all.
	 */
	@Test
	void everyModerationSettingIsReadByAResolverOfTheSameName() {
		Set<String> resolvers = Stream.of(ModerationPolicy.class.getMethods())
				.filter(method -> method.getDeclaringClass() == ModerationPolicy.class)
				.map(Method::getName)
				.collect(Collectors.toSet());

		assertThat(adminSettableModerationFields())
				.as("a nullable override nothing reads is a switch that does nothing")
				.allSatisfy(field -> assertThat(resolvers).contains(field));
	}

	/**
	 * And the knobs that are deliberately <em>not</em> settable stay that way.
	 *
	 * <p>Two of them are the reason this test is not just a mirror of the one above.
	 * {@code strictBlockThreshold} and {@code strictFlagThreshold} govern the
	 * categories {@link ModerationPolicy#isOverridable} returns false for — child
	 * sexual content and malware — and the invariant that they cannot be weakened is
	 * the single strongest claim this subsystem makes. Adding them to the settings
	 * document would keep every other test green while handing that claim to a web
	 * form, and to whoever gets hold of an admin session.
	 *
	 * <p>The other two are smaller but still not tenant policy:
	 * {@code supportedTypes} has to match what the deployed sidecar build actually
	 * decodes, and {@code freezeRefreshInterval} is bound to a {@code @Scheduled}
	 * placeholder that cannot be re-read at runtime anyway.
	 */
	@Test
	void theEnvOnlyModerationKnobsAreNotAdminSettable() {
		assertThat(adminSettableModerationFields())
				.as("see this test's javadoc before adding any of these")
				.doesNotContain("strictBlockThreshold", "strictFlagThreshold",
						"imageSupportedTypes", "supportedTypes",
						"freezeRefreshInterval", "escalationRetryDelay");
	}

	/**
	 * The persisted fields of the moderation settings block.
	 *
	 * <p>{@code @Transient} ones are excluded because they are derived status the
	 * controller computes for the UI ({@code escalationSecretConfigured}), not
	 * overrides anything is meant to resolve.
	 */
	private static List<String> adminSettableModerationFields() {
		return Stream.of(ServerSettings.Moderation.class.getDeclaredFields())
				.filter(field -> !field.isSynthetic())
				.filter(field -> !Modifier.isStatic(field.getModifiers()))
				.filter(field -> field.getAnnotation(Transient.class) == null)
				.map(Field::getName)
				.sorted()
				.toList();
	}

	/**
	 * Every surface has to be classified deliberately. A new constant defaulting to
	 * "not technical, not external" is usually wrong in the direction that hurts:
	 * an engineering surface without {@code technical()} starts refusing stack
	 * traces, and an externally authored one without {@code external()} is judged as
	 * if a colleague had signed in to write it.
	 */
	@Test
	void everySurfaceIsClassifiedAndTheExternalOnesAreTheExpectedSet() {
		List<ModerationSurface> external = Stream.of(ModerationSurface.values())
				.filter(ModerationSurface::external)
				.toList();

		assertThat(external).containsExactlyInAnyOrder(
				ModerationSurface.EXTERNAL_IMAGE,
				ModerationSurface.EMAIL_INGEST,
				ModerationSurface.EMAIL_ATTACHMENT,
				ModerationSurface.EMAIL_REPLY,
				ModerationSurface.GIT_COMMIT);
	}

	/** Binary surfaces must never be treated as text, or bytes get scored as words. */
	@Test
	void binarySurfacesAreNeverAlsoTechnicalTextExceptVoice() {
		for (ModerationSurface surface : ModerationSurface.values()) {
			if (surface.binary() && surface.technical()) {
				// Voice is the one both: the bytes are binary, but its transcript —
				// when a transcription tier is configured — is engineering speech.
				assertThat(surface).isEqualTo(ModerationSurface.VOICE);
			}
		}
	}

	// --- what Hinata *is*, under the DSA -------------------------------------

	/**
	 * Pins the set of routes reachable without authentication.
	 *
	 * <p>This is not a general security test — {@code SecurityConfig} is where that
	 * lives. It guards a <em>compliance</em> assumption that has no other
	 * representation in code, and that a perfectly reasonable feature would break
	 * without anyone noticing.
	 *
	 * <p>The whole moderation design rests on Hinata being a <b>hosting service</b>
	 * rather than an <b>online platform</b> under the DSA, and the difference is
	 * exactly whether content is "disseminated to the public". Every route below is
	 * authentication, setup, a webhook or a signed one-off link — none of them
	 * publishes user content. The day one does, Hinata becomes an online platform
	 * and Section 3 switches on: internal complaint handling (Art. 20),
	 * out-of-court dispute settlement (Art. 21), trusted flaggers (Art. 22) and
	 * transparency reporting (Art. 24) — none of which this codebase implements.
	 *
	 * <p>So a public share link, a public knowledge base or a public issue portal is
	 * not a feature decision that can be made in a pull request. Adding one fails
	 * here first, which is the point: the next person should meet this paragraph
	 * before shipping, not a compliance review afterwards.
	 *
	 * <p>Adding a route is allowed. Adding it <em>silently</em> is not.
	 */
	@Test
	void noRouteBecomesPubliclyReadableWithoutReopeningTheComplianceAssessment() {
		assertThat(publicRoutesIn("config/SecurityConfig.java"))
				.as("a new unauthenticated route may publish user content — see this test's javadoc")
				.containsExactlyInAnyOrder(
						// Authentication and account recovery.
						"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/2fa",
						"/api/v1/auth/sso/providers", "/api/v1/auth/sso/start/**",
						"/api/v1/auth/sso/exchange",
						"/api/v1/auth/invite/**", "/api/v1/auth/reset/**",
						"/api/v1/auth/register", "/api/v1/auth/verify-email",
						"/api/v1/auth/resend-verification",
						"/api/v1/me/email-change/confirm", "/api/v1/me/password-reset/confirm",
						// A signed, single-subject GDPR export — the one route that serves
						// user content unauthenticated, to the subject of that content.
						"/api/v1/me/export.pdf",
						// Branding and boot metadata, not user content.
						"/api/v1/meta", "/api/v1/meta/logo",
						"/api/v1/users/*/avatar",
						// Inbound integrations, authenticated by signature rather than session.
						"/api/v1/git/oauth/callback", "/api/v1/git/webhooks/**",
						"/api/v1/setup/status", "/api/v1/setup",
						"/actuator/health", "/actuator/health/**",
						// MCP OAuth 2.1 discovery + protocol; consent stays authenticated.
						"/.well-known/oauth-protected-resource",
						"/.well-known/oauth-protected-resource/**",
						"/.well-known/oauth-authorization-server",
						"/.well-known/hinata-connect-challenge",
						"/oauth/register", "/oauth/authorize", "/oauth/token",
						"/login/**", "/oauth2/**", "/saml2/**", "/error");
	}

	/**
	 * The API documentation runs on its own filter chain that permits everything it
	 * matches, so widening that {@code securityMatcher} is a second, quieter way to
	 * make a route public — one the list above would never see.
	 */
	@Test
	void theDocsChainStaysLimitedToDocumentation() {
		String source = read(MAIN.resolve("com/ahmadre/hinata/config/SecurityConfig.java"));
		Matcher matcher = Pattern.compile("\\.securityMatcher\\(([^)]*)\\)").matcher(source);

		assertThat(matcher.find()).isTrue();
		assertThat(quotedIn(matcher.group(1)))
				.containsExactlyInAnyOrder(
						"/v3/api-docs/**", "/docs/**", "/docs", "/scalar/**", "/webjars/**");
	}

	// --- freeze ----------------------------------------------------------------

	/**
	 * Every byte the product serves has to come through the one method the freeze
	 * guard is on.
	 *
	 * <p>{@code StorageService.getObject} is that method, and it is the only place
	 * allowed to call the backend's read. Eight callers reach it — the media proxy,
	 * the avatar route, the organisation logo, attachment download, voice playback,
	 * the e-mail reply that posts bytes to an external address — and a ninth that
	 * went straight to a backend would be invisible: no test would fail, nothing
	 * would log, and frozen material would simply keep being served.
	 *
	 * <p>The pin is on the caller set rather than on a behaviour because the mistake
	 * is a new call site, and a new call site is exactly what a behavioural test
	 * cannot see.
	 */
	@Test
	void everyStorageReadGoesThroughTheOneGuardedMethod() {
		assertThat(sourceContaining("backend.get("))
				.as("bytes must be read through StorageService.getObject, which is where the "
						+ "freeze guard is — see this test's javadoc")
				.containsExactly("storage/StorageService.java");
	}

	/**
	 * The two unauthenticated routes that serve user content have to know about
	 * freeze.
	 *
	 * <p>{@link #noRouteBecomesPubliclyReadableWithoutReopeningTheComplianceAssessment}
	 * already declares {@code /api/v1/users/*&#47;avatar} and
	 * {@code /api/v1/me/export.pdf} as the public content routes. They are also the
	 * two that every viewer-parameterised mechanism in the codebase misses by
	 * construction, because there is no viewer: the export PDF prints every comment
	 * its subject authored, over a signed link that needs no session, so the author
	 * of frozen content retrieves it by mailing themselves one.
	 *
	 * <p>The avatar route is covered by the byte chokepoint above rather than by a
	 * reference of its own — {@code AvatarService.load} goes through
	 * {@code getObject} — so what is pinned here is the one that renders text.
	 */
	@Test
	void theUnauthenticatedContentRoutesAreFreezeAware() {
		assertThat(read(MAIN.resolve("com/ahmadre/hinata/me/DataExportPdfService.java")))
				.as("the unauthenticated GDPR export prints comment text verbatim")
				.contains("FrozenContentService")
				.contains("FrozenTargetType.COMMENT")
				.contains("FrozenTargetType.ISSUE");
		assertThat(read(MAIN.resolve("com/ahmadre/hinata/storage/StorageService.java")))
				.as("the avatar and logo routes are unauthenticated and reach bytes through here")
				.contains("frozen.assertObjectReadable");
	}

	/**
	 * Every reportable kind of thing has to be freezable.
	 *
	 * <p>The two enums are deliberately separate — {@link com.ahmadre.hinata.moderation.freeze.FrozenTargetType}
	 * carries {@code OBJECT}, which nobody reports and which is the only thing that
	 * can cover an inline image — but the five that overlap must stay in step. Adding
	 * a reportable kind without a freeze counterpart would compile, pass every test,
	 * and produce a report of the one category that must freeze against a target the
	 * freeze path silently skips.
	 *
	 * <p><b>This check proves the names line up and nothing else</b>, which is worth
	 * saying because for a long time it was the only thing standing behind
	 * {@code ATTACHMENT} and {@code USER} — both of which passed it while being
	 * enforced from precisely nowhere. Name equality is not enforcement, and
	 * {@link #everyFreezableKindIsActuallyEnforcedSomewhere} is the check that says
	 * so. Keep both: this one catches a new reportable kind, that one catches a
	 * freezable kind nobody wired up.
	 */
	@Test
	void everyReportTargetTypeHasAFreezeCounterpart() {
		List<String> reportable = Stream.of(ContentReport.TargetType.values())
				.map(Enum::name)
				.toList();
		List<String> freezable = Stream.of(FrozenTargetType.values())
				.map(Enum::name)
				.toList();

		assertThat(freezable)
				.as("a reportable kind that cannot be frozen is a hole in the freeze trigger")
				.containsAll(reportable);
		assertThat(freezable)
				.as("OBJECT is the one freezable kind with no report counterpart — it is what "
						+ "covers an inline image, which has no database row at all")
				.containsExactlyInAnyOrderElementsOf(
						Stream.concat(reportable.stream(), Stream.of("OBJECT")).toList());
	}

	/**
	 * Every constant in {@link FrozenTargetType} is consulted by at least one read
	 * path, and every one of them resolves the bytes it owns.
	 *
	 * <p>This is the check the enum-name comparison above was mistaken for. Two
	 * constants shipped fully wired on paper and dead in practice:
	 * {@code ATTACHMENT} was read by nothing and given no object keys, so freezing a
	 * reported file left it downloadable; {@code USER} was read by nothing at all, so
	 * freezing an account was a row in a registry and a display name that carried on
	 * being served. Both passed every behavioural test in the suite, because there is
	 * no behaviour to test when nothing asks.
	 *
	 * <p>Named production files rather than a bare "mentioned somewhere" scan,
	 * because the answer to "where is this enforced" is the thing a reviewer needs
	 * and a grep for the constant would be satisfied by the enum's own declaration.
	 * A new constant fails here until somebody says where it is enforced — and a
	 * constant that <em>cannot</em> be enforced should be deleted rather than added
	 * to this map.
	 *
	 * <p><b>Like every check in this class it matches on a name, so it survives a
	 * mutation that guts the guard it points at</b> — the review tried exactly that.
	 * That is not a defect in the check, it is its scope: it catches the kind nobody
	 * wired up, which no behavioural test can, and the behaviour is asserted
	 * elsewhere. {@code FrozenUserSurfaceMongoTest} covers {@code USER} end to end
	 * (directory search, the batch id lookup, the people category and the
	 * unauthenticated avatar route); {@code FrozenIssueListMongoTest} and
	 * {@code FrozenIssueSurfacesMongoTest} cover {@code ISSUE};
	 * {@code FreezeCompletenessTest} covers {@code ATTACHMENT} and {@code OBJECT}.
	 * Both kinds of check are load-bearing and neither substitutes for the other.
	 */
	@Test
	void everyFreezableKindIsActuallyEnforcedSomewhere() {
		Map<FrozenTargetType, List<String>> enforcedIn = new EnumMap<>(Map.of(
				FrozenTargetType.ISSUE, List.of(
						// by-id read, the list/board/backlog query, mention chips, hierarchy
						"issue/IssueService.java",
						"search/SearchService.java",
						"me/DataExportPdfService.java"),
				FrozenTargetType.COMMENT, List.of(
						"issue/IssueService.java",
						"me/DataExportPdfService.java",
						"notification/NotificationRedactor.java"),
				FrozenTargetType.ARTICLE, List.of(
						"article/ArticleController.java",
						"search/SearchService.java"),
				// A subdocument with no row of its own: enforced through the object keys
				// its freeze resolves, which the byte chokepoint then refuses.
				FrozenTargetType.ATTACHMENT, List.of(
						"moderation/freeze/FrozenObjectKeys.java"),
				FrozenTargetType.USER, List.of(
						"user/UserDirectoryService.java",
						"search/SearchService.java",
						"me/AvatarService.java"),
				FrozenTargetType.OBJECT, List.of(
						"storage/StorageService.java",
						"moderation/freeze/FrozenObjectKeys.java")));

		assertThat(enforcedIn.keySet())
				.as("a new freezable kind needs a read path that consults it, named here")
				.containsExactlyInAnyOrder(FrozenTargetType.values());
		enforcedIn.forEach((type, files) -> files.forEach(file -> assertThat(
				read(MAIN.resolve("com/ahmadre/hinata").resolve(file)))
				.as("%s claims to be enforced in %s", type, file)
				.containsAnyOf(spellings(type))));
	}

	/**
	 * How a file may spell "I consult this kind of freeze".
	 *
	 * <p>Most call sites name the constant. Two do not, deliberately:
	 * {@code FrozenObjectKeys} resolves every kind in one exhaustive switch, and
	 * {@code StorageService} reaches {@code OBJECT} through the enum-free byte API —
	 * which is the whole design of that guard, since it is handed a storage key and
	 * has no entity to name.
	 */
	private static String[] spellings(FrozenTargetType type) {
		if (type == FrozenTargetType.OBJECT) {
			return new String[] { "FrozenTargetType.OBJECT", "case OBJECT", "isFrozenObject",
					"assertObjectReadable", "assertNoObjectFrozen" };
		}
		return new String[] { "FrozenTargetType." + type.name(), "case " + type.name() };
	}

	/**
	 * The freeze snapshot is re-read on a timer.
	 *
	 * <p>It is held per JVM, so behind more than one replica a freeze raised on
	 * instance A reaches instance B only when B next reads the registry. Without a
	 * scheduled read that is "never, until it restarts" — the feature silently not
	 * working in exactly the deployments that scale, and nothing about the code looks
	 * different. There is no message bus in this product, so the poll is the
	 * mechanism; the interval is the documented exposure window.
	 */
	@Test
	void theFreezeRegistryIsReReadOnATimer() {
		String source = read(MAIN.resolve(
				"com/ahmadre/hinata/moderation/freeze/FrozenContentService.java"));

		assertThat(source)
				.as("a per-JVM snapshot with no scheduled refresh is not enforced across replicas")
				.contains("@Scheduled")
				.contains("hinata.moderation.freeze-refresh-interval");
	}

	/**
	 * Object keys are resolved in one place, from the target.
	 *
	 * <p>They used to be resolved by the report path and passed as {@code List.of()}
	 * by the admin path, so the same freeze covered different bytes depending on who
	 * raised it. A second copy appearing anywhere is that divergence coming back, and
	 * it is invisible: both callers compile, both write a row, and only one of them
	 * stops serving the file.
	 */
	@Test
	void onlyTheSharedResolverDecidesWhichBytesAFreezeCovers() {
		assertThat(sourceContaining("/api/v1/media/([0-9a-fA-F-]{36})"))
				.as("one extraction of inline media references, shared by the orphan sweep and "
						+ "the freeze — a second regex drifts, and it drifts toward the sweep "
						+ "deleting bytes the freeze believes it is preserving")
				.containsExactly("media/MediaReferences.java");
		assertThat(sourceContaining("MediaReferences."))
				.as("both consumers ask the shared extractor")
				.containsExactlyInAnyOrder(
						"media/MediaGarbageCollector.java",
						"moderation/freeze/FrozenObjectKeys.java");
		assertThat(read(MAIN.resolve(
				"com/ahmadre/hinata/moderation/freeze/FrozenContentService.java")))
				.as("freeze() resolves the target's own bytes, so no caller can under-supply them")
				.contains("objectKeys.of(");
	}

	/**
	 * "No DSA Art. 17 statement of reasons was issued" is recorded as the absence of
	 * data, not as a constant.
	 *
	 * <p>The field was {@code boolean statementWithheld = true}, and a constant is the
	 * one shape a compliance field must not have. It made every assertion about the
	 * value pass whether or not any code ever decided it; it asserted a fact about
	 * every freeze past and future on behalf of an operator who was never asked; and
	 * it could not be made false the day a notice path exists, so it was not a record
	 * of anything — it was a comment with a getter.
	 *
	 * <p>A nullable {@code statementIssuedAt} cannot fail that way. Nothing has to
	 * write it for "none was issued" to be honest, and a statement that <em>is</em>
	 * issued has somewhere to go. Checked here as a type rather than as a value,
	 * because the value being null is exactly what a behavioural test cannot
	 * distinguish from a field nobody thought about — and the audit meta is checked
	 * because the log is the only place an operator can answer the question from.
	 */
	@Test
	void theStatementOfReasonsIsRecordedAsAbsentDataRatherThanAConstant() throws Exception {
		assertThat(FrozenContent.class.getDeclaredField("statementIssuedAt").getType())
				.as("a boolean here can only ever mean one thing, which is what made the "
						+ "previous field a constant asserting a claim nobody made")
				.isEqualTo(java.time.Instant.class);
		assertThat(FrozenContent.builder().build().getStatementIssuedAt())
				.as("the default is no data, so nothing has to be written for it to be true")
				.isNull();
		assertThat(read(MAIN.resolve(
				"com/ahmadre/hinata/moderation/freeze/FrozenContentService.java")))
				.as("the audit entry must carry it, or the record answers nothing")
				.contains("\"statementIssuedAt\"");
	}

	/**
	 * The destructive paths consult the registry.
	 *
	 * <p>{@code FrozenContent}'s javadoc says "a row here is what lets the deletion
	 * and garbage-collection paths refuse", and for a while nothing did: the issue
	 * delete resolved through the ACL-free lookup and the project cascade asked
	 * nothing at all. A cascade that quietly stops refusing is the definition of a
	 * silent failure — the content is gone and the log says a deletion succeeded.
	 *
	 * <p><b>This is a new-call-site detector and not a behavioural check, and the
	 * distinction matters.</b> It matches on a name, so a mutation that guts the
	 * method the name refers to leaves it green — it survived exactly that when the
	 * review tried it. What it catches is the case no behavioural test can: a
	 * destructive path added tomorrow that never asks. The behaviour is asserted in
	 * {@code FrozenEvidenceSurvivesDeletionTest} — {@code anAdminCannotHardDeleteAFrozenIssue}
	 * for the issue delete, {@code aProjectCascadeRefusesWhenOneOfItsIssuesIsFrozen}
	 * for the cascade, and {@code deletingAFrozenAttachmentIsRefusedAndLeavesTheMetadataIntact}
	 * for the attachment route — and neither kind is redundant with the other.
	 */
	@Test
	void theDestructivePathsRefuseFrozenContent() {
		assertThat(read(MAIN.resolve("com/ahmadre/hinata/issue/IssueService.java")))
				.as("hard delete cascades over comments and sub-tasks")
				.contains("assertNothingFrozenUnder");
		assertThat(read(MAIN.resolve("com/ahmadre/hinata/deletion/DeletionService.java")))
				.as("the project cascade destroys every issue, comment, attachment and article")
				.contains("assertNothingFrozenIn");
		assertThat(read(MAIN.resolve("com/ahmadre/hinata/storage/StorageService.java")))
				.as("the orphan sweep and every other best-effort delete funnel through here")
				.contains("frozen.isFrozenObject");
		assertThat(read(MAIN.resolve("com/ahmadre/hinata/storage/AttachmentController.java")))
				.as("removing the subdocument is destructive even though the bytes survive: it "
						+ "leaves a file nothing points at")
				.contains("assertNotFrozen(FrozenTargetType.ATTACHMENT");
	}

	/**
	 * Only an allow-listed set of files reads {@code Issue} out of Mongo directly,
	 * and every one that serves what it reads goes through {@link FrozenIssues}.
	 *
	 * <p>This is the check that makes the chokepoint worth having. The audit found
	 * nine callers that had gone around {@code IssueService.getForUser} and
	 * {@code IssueService.search} — the board, the Gantt chart, the dashboard, the
	 * weekly summary, the reports, the due-date reminder — and not one of them was an
	 * independent mistake. Each reached for {@code mongo.find(query, Issue.class)}
	 * because that was the shortest route to the rows it wanted, and none had any
	 * reason to know an unrelated moderation feature had an opinion about the result.
	 * Fixing nine call sites fixes nine; an allow-list fails the build when the tenth
	 * appears, which is the difference between a fix and the fourth review.
	 *
	 * <p>Shaped after {@code everyStorageReadGoesThroughTheOneGuardedMethod}, which
	 * pins the byte reads to one file in one assertion. Three lists rather than one,
	 * because the exemptions are real and have to be stated rather than hidden inside
	 * a wider allow-list: a seeder, a cascade and two write helpers do not serve
	 * anything to a reader, and requiring them to filter would be cargo cult.
	 *
	 * <p><b>What this does not yet pin, said plainly.</b> The
	 * {@code IssueRepository}'s derived finders are the other way around the seam, and
	 * thirteen classes inject it. The two that this work package fixed are listed
	 * below and asserted; the rest — {@code SprintService}, {@code IssueMoveService},
	 * {@code TimeTrackingService}, {@code ProjectTools} — are still unfiltered, and an
	 * allow-list naming them as permitted would read as approval for a state nobody
	 * approved. They are recorded as open work instead, which is the honest place for
	 * them until they are closed.
	 */
	@Test
	void everyIssueReadThatServesItsRowsGoesThroughTheFreezeChokepoint() {
		// Reads Issue out of Mongo AND hands what it reads to a caller.
		List<String> serving = List.of(
				"dashboard/DashboardService.java",
				"issue/IssueService.java",
				"notification/DueDateReminderJob.java",
				"report/ReportController.java",
				"weeklysummary/WeeklySummaryService.java");
		// Touches Issue in Mongo but serves nothing, each for a stated reason.
		List<String> nonServing = List.of(
				// Writes only: $push/$pull of an attachment subdocument.
				"storage/AttachmentStore.java",
				// Destructive cascade. It must REFUSE rather than filter, which is a
				// different guard (assertNothingFrozenIn) and a different test.
				"deletion/DeletionService.java",
				// Demo fixtures, written not read.
				"demo/DemoSeeder.java",
				// A count during account deletion; serves nothing.
				"user/UserService.java",
				// The chokepoint's own definition and its javadoc.
				"moderation/freeze/FrozenIssues.java");
		// Serve their rows too, but reach them through derived repository finders
		// rather than a Query — which is exactly how they came to inherit none of
		// IssueService.search's filters.
		List<String> servingThroughFinders = List.of(
				"board/BoardController.java",
				"gantt/GanttController.java");

		assertThat(sourceContaining("Issue.class)"))
				.as("a new file reading Issue out of Mongo has to declare itself here and say "
						+ "which side of the line it is on")
				.containsExactlyInAnyOrderElementsOf(
						Stream.concat(serving.stream(), nonServing.stream()).sorted().toList());
		Stream.concat(serving.stream(), servingThroughFinders.stream())
				.forEach(file -> assertThat(read(MAIN.resolve("com/ahmadre/hinata").resolve(file)))
						.as("%s serves the issues it reads and must consult the freeze chokepoint", file)
						.containsAnyOf("frozenIssues.", "frozen.exclusion("));
	}

	/**
	 * The MCP tools reach the issue list through the same service method the app
	 * does.
	 *
	 * <p>{@code search_issues} and {@code list_my_issues} are a second front door to
	 * {@code IssueService.search}, and a tool that grew its own query would inherit
	 * none of that method's filters — access, archive or freeze — while looking
	 * exactly as correct.
	 */
	@Test
	void theMcpIssueToolsRouteThroughTheFilteredServiceQuery() {
		String source = read(MAIN.resolve("com/ahmadre/hinata/mcp/IssueReadTools.java"));

		assertThat(source).contains("issueService.search(");
		assertThat(source)
				.as("an MCP tool must not build its own issue query")
				.doesNotContain("MongoTemplate")
				.doesNotContain("IssueRepository");
	}

	/**
	 * No implementation of the known-illegal hash port ships, and none ever may.
	 *
	 * <p>The reasons are on the interface: the hash-matching programmes vet the
	 * operator organisation rather than the software, their terms forbid
	 * redistributing credentials, and their lists are server-side precisely so a
	 * local copy cannot be used as an offline oracle to test evasion against. All
	 * three are arguments about the <em>shipped artefact</em>, which is what this
	 * checks — an operator implementing it in their own deployment is the intended
	 * use and is not affected.
	 */
	@Test
	void noKnownIllegalHashProviderImplementationShips() {
		assertThat(sourceContaining("implements KnownIllegalHashProvider"))
				.as("no implementation may ship — see KnownIllegalHashProvider's javadoc")
				.isEmpty();
	}

	/** Everything not named above must still require a session. */
	@Test
	void everythingElseRemainsAuthenticated() {
		String source = read(MAIN.resolve("com/ahmadre/hinata/config/SecurityConfig.java"));

		assertThat(source).contains(".anyRequest().authenticated()");
		assertThat(source).contains("\"/api/v1/admin/**\").hasRole(\"ADMIN\")");
	}

	/** The route patterns handed to {@code permitAll()} in [file]. */
	private static List<String> publicRoutesIn(String file) {
		String source = read(MAIN.resolve("com/ahmadre/hinata").resolve(file));
		Matcher matcher = Pattern
				.compile("\\.requestMatchers\\(([\\s\\S]*?)\\)\\s*\\.permitAll\\(\\)")
				.matcher(source);

		assertThat(matcher.find())
				.as("could not locate the permitAll block — this parser has drifted and "
						+ "would otherwise pass vacuously")
				.isTrue();
		return quotedIn(matcher.group(1));
	}

	private static List<String> quotedIn(String block) {
		return Pattern.compile("\"([^\"]+)\"").matcher(block).results()
				.map(result -> result.group(1))
				.toList();
	}

	/** Repo-relative paths of production sources containing [needle]. */
	private static List<String> sourceContaining(String needle) {
		Map<String, String> hits = new TreeMap<>();
		try (Stream<Path> files = Files.walk(MAIN)) {
			files.filter(path -> path.toString().endsWith(".java"))
					.forEach(path -> {
						if (read(path).contains(needle)) {
							hits.put(MAIN.resolve("com/ahmadre/hinata").relativize(path).toString(), "");
						}
					});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return List.copyOf(hits.keySet());
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
