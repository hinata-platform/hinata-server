package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
import com.ahmadre.hinata.moderation.report.ContentReport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
