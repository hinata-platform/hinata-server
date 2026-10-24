package com.ahmadre.hinata.moderation.filter;

import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.moderation.ContentReportService;
import com.ahmadre.hinata.moderation.ModerationStats;
import com.ahmadre.hinata.moderation.StatMetric;
import com.ahmadre.hinata.richtext.TextField;
import com.ahmadre.hinata.richtext.TextGate;
import com.ahmadre.hinata.richtext.TextVerdict;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The text check on this instance (HIN-176): the shipped lists and the administrator's own terms,
 * one pass over a field and one verdict. Refusing needs a term of weight {@link #BLOCK_AT} or an
 * own term; flagging needs the switch, a field with content to look at afterwards and a shipped
 * term of weight {@link #FLAG_AT}. Own terms never flag.
 *
 * <p>Built at start and again on every change of the settings, and read anew when older than
 * {@link #MAX_AGE}: a change reaches only the instance that saved it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LexiconTextGate implements TextGate {

	/** A find of this weight refuses the text. */
	static final int BLOCK_AT = 85;

	/** A find of this weight can go before moderation. */
	static final int FLAG_AT = 55;

	/**
	 * The most a find inside code, logs or identifiers weighs: between {@link #FLAG_AT} and
	 * {@link #BLOCK_AT}, so a slur in a code block can still reach moderation while a pasted log
	 * file is never refused. Threats and the safety of children are not softened by it: no field
	 * and no span makes them weigh less.
	 */
	static final int TECHNICAL_SPAN_CEILING = 60;

	static final Duration MAX_AGE = Duration.ofSeconds(30);

	/** Which category leads when two finds weigh the same. */
	private static final List<ReportCategory> PRECEDENCE = List.of(ReportCategory.CHILD_SAFETY,
			ReportCategory.THREAT, ReportCategory.SEXUAL, ReportCategory.HARASSMENT, ReportCategory.ILLEGAL,
			ReportCategory.SPAM, ReportCategory.PRIVACY, ReportCategory.OTHER);

	private final SettingsService settings;
	private final ContentReportService reports;
	private final ModerationStats stats;
	private final Clock clock;

	private final Lexicon bundled = Lexicon.bundled();
	private volatile Snapshot cached;

	private record Snapshot(ServerSettings.Filter filter, Lexicon lexicon, Instant readAt) {
	}

	/** A find and what it weighs where it was found. */
	record Find(Lexicon.Hit hit, int weight, boolean technical) {
	}

	/** What one text holds: the finds, the exceptions that kept a rule word from counting. */
	record Judged(List<Find> finds, Set<String> exempt) {
	}

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		cached = snapshotOf(event.settings());
	}

	@Override
	public TextVerdict check(String text, TextField field, User author, String before) {
		TextVerdict verdict = evaluate(text, field, before);
		if (verdict.blocked()) {
			count(StatMetric.TEXT_REFUSED, verdict);
		}
		return verdict;
	}

	@Override
	public void flagged(ContentRef ref, TextField field, TextVerdict verdict, User author) {
		reports.fileAutomated(ref, verdict.category(), verdict.newFlagTerms(), "auto:" + field.name());
		count(StatMetric.TEXT_FLAGGED, verdict);
	}

	/** The verdict on [text] as [field], without counting anything: also for the admin's test. */
	TextVerdict evaluate(String text, TextField field, String before) {
		Snapshot snapshot = snapshot();
		List<Find> finds = judge(snapshot.lexicon(), text, field).finds();
		if (finds.isEmpty()) {
			return TextVerdict.ALLOW;
		}
		ServerSettings.Filter filter = snapshot.filter();
		List<Find> refusing = finds.stream().filter(find -> find.hit().own()
				|| filter.blocks() && find.weight() >= BLOCK_AT).toList();
		if (!refusing.isEmpty()) {
			return new TextVerdict(TextVerdict.Verdict.BLOCK, lead(refusing), terms(refusing), List.of());
		}
		if (!filter.flags() || !field.flaggable()) {
			return TextVerdict.ALLOW;
		}
		List<Find> flagging = flagging(finds);
		if (flagging.isEmpty()) {
			return TextVerdict.ALLOW;
		}
		Set<String> seen = new LinkedHashSet<>(terms(flagging(judge(snapshot.lexicon(), before, field).finds())));
		List<String> fresh = terms(flagging).stream().filter(term -> !seen.contains(term)).toList();
		return new TextVerdict(TextVerdict.Verdict.FLAG, lead(flagging), terms(flagging), fresh);
	}

	/** Every find in [text] as [field]; code, logs and identifiers judged apart in a field that has them. */
	Judged judge(String text, TextField field) {
		return judge(snapshot().lexicon(), text, field);
	}

	/**
	 * The whole text first: without a find in it there is nothing code could excuse, and taking
	 * code apart is the costly half, so the ordinary text never pays for it. With a find, prose and
	 * code are judged apart, and a threat or anything about the safety of children counts from the
	 * whole text too, so that putting one word of it in backticks hides nothing.
	 */
	private static Judged judge(Lexicon lexicon, String text, TextField field) {
		if (text == null || text.isBlank()) {
			return new Judged(List.of(), Set.of());
		}
		Lexicon.Scan whole = lexicon.scan(text);
		if (!field.technical() || whole.hits().isEmpty()) {
			return new Judged(finds(whole, false), whole.exempt());
		}
		TechnicalTextStripper.Result split = TechnicalTextStripper.strip(text);
		Lexicon.Scan prose = lexicon.scan(split.prose());
		Lexicon.Scan technical = lexicon.scan(split.technical());
		List<Find> finds = new ArrayList<>(finds(prose, false));
		finds.addAll(finds(technical, true));
		Set<String> counted = new LinkedHashSet<>(finds.stream().filter(find -> !find.technical())
				.map(find -> find.hit().term()).toList());
		for (Find find : finds(whole, false)) {
			if (neverSoftened(find.hit()) && counted.add(find.hit().term())) {
				finds.add(find);
			}
		}
		Set<String> exempt = new LinkedHashSet<>(whole.exempt());
		exempt.addAll(prose.exempt());
		exempt.addAll(technical.exempt());
		return new Judged(List.copyOf(finds), Set.copyOf(exempt));
	}

	private static List<Find> finds(Lexicon.Scan scan, boolean technical) {
		return scan.hits().stream().map(hit -> new Find(hit, weightOf(hit, technical), technical)).toList();
	}

	private static int weightOf(Lexicon.Hit hit, boolean technical) {
		return technical && !hit.own() && !neverSoftened(hit) ? Math.min(hit.weight(), TECHNICAL_SPAN_CEILING)
				: hit.weight();
	}

	/** Threats and the safety of children: no field and no span makes them weigh less. */
	private static boolean neverSoftened(Lexicon.Hit hit) {
		return hit.category() == ReportCategory.CHILD_SAFETY || hit.category() == ReportCategory.THREAT;
	}

	private static List<Find> flagging(List<Find> finds) {
		return finds.stream().filter(find -> !find.hit().own() && find.weight() >= FLAG_AT).toList();
	}

	/** The category of the heaviest find; the more serious one when two weigh the same. */
	private static ReportCategory lead(List<Find> finds) {
		return finds.stream()
				.max(Comparator.comparingInt(Find::weight)
						.thenComparing(find -> -PRECEDENCE.indexOf(find.hit().category())))
				.map(find -> find.hit().category()).orElse(ReportCategory.OTHER);
	}

	/** The terms found, each once, in the order of the text. */
	private static List<String> terms(List<Find> finds) {
		return List.copyOf(new LinkedHashSet<>(finds.stream().map(find -> find.hit().term()).toList()));
	}

	/** The lexicon as the settings stand, at most {@link #MAX_AGE} old. */
	private Snapshot snapshot() {
		Snapshot snapshot = cached;
		if (snapshot == null || !snapshot.readAt().plus(MAX_AGE).isAfter(clock.instant())) {
			snapshot = snapshotOf(settings.get());
			cached = snapshot;
		}
		return snapshot;
	}

	private Snapshot snapshotOf(ServerSettings stored) {
		ServerSettings.Filter filter = stored == null || stored.getModeration() == null
				|| stored.getModeration().getFilter() == null ? new ServerSettings.Filter()
				: stored.getModeration().getFilter();
		return new Snapshot(filter, bundled.with(filter.ownBlockTerms(), filter.ownAllowTerms()), clock.instant());
	}

	/**
	 * By the category of [verdict], which the yearly figures report by (HIN-178). In the request
	 * it counts for; a count that fails must not fail the request.
	 */
	private void count(StatMetric metric, TextVerdict verdict) {
		ReportCategory category = verdict.category() == null ? ReportCategory.OTHER : verdict.category();
		try {
			stats.count(metric, category.name());
		}
		catch (RuntimeException ex) {
			log.warn("Counting {} for {} failed: {}", metric, category, ex.getMessage());
		}
	}
}
