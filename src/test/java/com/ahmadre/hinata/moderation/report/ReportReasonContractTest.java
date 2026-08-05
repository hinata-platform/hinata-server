package com.ahmadre.hinata.moderation.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the report-reason vocabulary the Flutter client sends against the enum this
 * server accepts.
 *
 * <p>The two are deliberately not the same list. The reporter vocabulary is coarser
 * than the classifier taxonomy — a person filing a report is describing what they
 * saw, not labelling it, and a picker with thirteen near-synonyms gets its first row
 * chosen every time — so the client folds {@code VIOLENT_THREAT} into violence and
 * {@code EXTREMISM}/{@code MALWARE} into illegal. A subset is fine. A value the
 * client can send and the server has never heard of is not: Jackson rejects the
 * whole request with a 400, and the reason it happens to break is the one at the top
 * of the picker.
 *
 * <p>That is not hypothetical — {@code SPAM} shipped on the client with no server
 * constant, and the client's own contract test could not see it, because a test that
 * checks an enum against itself passes against a broken wire. Only a check that
 * crosses the repository boundary catches this class of bug.
 *
 * <p>The check is skipped when the app checkout is not next to the server one, so a
 * server-only CI clone still builds. That is a real limitation and the reason this
 * is a guard rather than a guarantee: it protects the workspace where both repos are
 * edited together, which is where the drift is introduced.
 */
class ReportReasonContractTest {

	/** The client enum, as a sibling checkout of this repository. */
	private static final Path CLIENT_MODELS = Path.of(
			"../hinata-app/lib/core/models/moderation_models.dart");

	/** {@code spam('SPAM', null),} — the wire name is the first quoted argument. */
	private static final Pattern WIRE = Pattern.compile(
			"^\\s*+[a-zA-Z]\\w*+\\('([A-Z_]++)'", Pattern.MULTILINE);

	static boolean clientCheckoutPresent() {
		return Files.isRegularFile(CLIENT_MODELS);
	}

	@Test
	@EnabledIf("clientCheckoutPresent")
	void everyReasonTheClientCanSendIsAcceptedHere() {
		List<String> clientWire = clientReasons();

		assertThat(clientWire)
				.as("parsed no reasons from %s — the parser has drifted, "
						+ "which would make this test silently vacuous", CLIENT_MODELS)
				.isNotEmpty();

		List<String> serverNames = Stream.of(ContentReport.ReportReason.values())
				.map(Enum::name)
				.toList();

		assertThat(serverNames)
				.as("the client sends these; an unknown one is a 400 on the report endpoint")
				.containsAll(clientWire);
	}

	/**
	 * The reverse direction is intentionally NOT asserted equal — the server having
	 * reasons the picker does not offer is the designed state. It is asserted
	 * non-empty only so that a rename on this side cannot leave the client mapping
	 * onto nothing.
	 */
	@Test
	@EnabledIf("clientCheckoutPresent")
	void theClientOffersAWorkableSubsetRatherThanNothing() {
		assertThat(clientReasons())
				.contains("OTHER")
				.hasSizeGreaterThanOrEqualTo(5);
	}

	@Test
	void spamIsAcceptedAndCarriesNoCategory() {
		// It reached production on the client before it existed here, and it is the
		// first row of the picker, so it is worth pinning on its own.
		ContentReport.ReportReason spam = ContentReport.ReportReason.valueOf("SPAM");

		assertThat(spam.category())
				.as("nothing in the pipeline detects spam; claiming a category would "
						+ "put reports into a queue lane no classifier ever fills")
				.isNull();
	}

	private static List<String> clientReasons() {
		String source = read(CLIENT_MODELS);
		int start = source.indexOf("enum ReportReason");
		int end = source.indexOf('}', start);
		Matcher matcher = WIRE.matcher(source.substring(start, end));
		return matcher.results().map(result -> result.group(1)).toList();
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
