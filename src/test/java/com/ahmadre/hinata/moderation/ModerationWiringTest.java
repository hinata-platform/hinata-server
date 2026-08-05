package com.ahmadre.hinata.moderation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
