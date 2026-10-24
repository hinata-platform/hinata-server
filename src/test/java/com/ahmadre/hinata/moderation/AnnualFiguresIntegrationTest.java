package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The yearly figures (HIN-178) follow what happened, counted when it happened: a notice, the
 * action on it, the appeal and its outcome, a suspension of moderation's own, and counts that
 * arrive at once.
 */
class AnnualFiguresIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	private ModerationCaseController moderation;
	@Autowired
	private MemberModerationController member;
	@Autowired
	private ContentReportService reports;
	@Autowired
	private RichTextService richText;
	@Autowired
	private SettingsService settings;
	@Autowired
	private AnnualFigures figures;
	@Autowired
	private ModerationStats stats;
	@Autowired
	private Clock clock;

	private User mira;
	private User rita;
	private int year;

	@BeforeEach
	void seedModerators() {
		contactIs(settings, DecisionIntegrationTest.CONTACT);
		mira = user("mira", Role.MODERATOR);
		rita = user("rita", Role.MODERATOR);
		year = LocalDate.now(clock).getYear();
	}

	@Test
	void aNoticeItsActionAndTheReversedAppealStandInTheFigures() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		IssueComment insult = issues.addComment(issue.getId(),
				richText.fromLexical(doc(DecisionIntegrationTest.INSULT)), null, author);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, insult.getId(),
				issue.getId()), ReportCategory.HARASSMENT, null, null, false), reader);
		ModerationCase c = mongo.findOne(Query.query(Criteria.where("target.id").is(insult.getId())),
				ModerationCase.class);
		actingAs(mira);
		moderation.decide(c.getId(), DecisionIntegrationTest.action(0, RemovalScope.WHOLE));
		followUpsDone();
		actingAs(author);
		member.appeal(c.getId(), new AppealService.AppealRequest("Das war Ironie unter Kollegen."));
		actingAs(rita);
		long rev = mongo.findById(c.getId(), ModerationCase.class).getRev();
		moderation.decideAppeal(c.getId(), new AppealService.AppealDecisionRequest(rev,
				AppealService.AppealDecisionRequest.Result.REVERSE, "Im Zusammenhang gelesen ist es Satire."));
		followUpsDone();

		Map<String, List<List<String>>> parts = figures.parts(year);
		List<List<String>> notices = parts.get("4_notices");
		assertThat(cell(notices, "TOTAL", AnnualFigures.NOTICES)).isEqualTo("1");
		assertThat(cell(notices, "KEYWORD_CYBER_HARASSMENT", AnnualFigures.NOTICES)).isEqualTo("1");
		assertThat(cell(notices, "TOTAL", AnnualFigures.RULES_ACTIONS)).isEqualTo("1");
		assertThat(cell(notices, "TOTAL", AnnualFigures.MEDIAN_ACTION)).isEqualTo("0");
		List<List<String>> appeals = parts.get("7_appeals_recidivism");
		String internal = "Number of complaints submitted to the internal-complaints mechanism";
		assertThat(indicator(appeals, internal, "Total number")).isEqualTo("1");
		assertThat(indicator(appeals, internal, "Decisions reversed")).isEqualTo("1");
		assertThat(indicator(appeals, "Complaint regarding a decision to remove or disable access to or restrict "
				+ "visibility of information", "Total number")).isEqualTo("1");
		// Nothing of moderation's own in this year.
		assertThat(cell(parts.get("6_own_initiative_TC"), "TOTAL", AnnualFigures.MEASURES)).isEqualTo("0");
	}

	@Test
	void aSuspensionOfModerationsOwnStandsAmongTheMeasuresOnItsOwnInitiative() {
		actingAs(mira);
		String id = moderation.open(new CaseService.OwnCaseRequest(new TargetRef(ContentKind.PERSON, author.getId(),
				null), ReportCategory.HARASSMENT, "Beleidigt seit Wochen Kolleginnen in Kommentaren.")).get("id");
		long rev = mongo.findById(id, ModerationCase.class).getRev();
		moderation.decide(id, new DecisionService.DecisionRequest(rev, Decision.Outcome.ACTION, null,
				Decision.Ground.RULES, ReportCategory.HARASSMENT, DecisionIntegrationTest.FACTS,
				"2. Kein Herabsetzen anderer", null, null, false, null, null, false,
				new DecisionService.SuspensionChoice(Decision.SuspensionLength.DAYS_7)));
		followUpsDone();

		List<List<String>> rules = figures.parts(year).get("6_own_initiative_TC");
		assertThat(cell(rules, "TOTAL", AnnualFigures.MEASURES)).isEqualTo("1");
		assertThat(cell(rules, "TOTAL", AnnualFigures.SUSPENSION)).isEqualTo("1");
		assertThat(cell(rules, "KEYWORD_CYBER_HARASSMENT", AnnualFigures.SUSPENSION)).isEqualTo("1");
		// A person decided it: no measure by automated means alone.
		assertThat(cell(rules, "TOTAL", AnnualFigures.AUTOMATED)).isEqualTo("0");
		// The own initiative is no notice.
		assertThat(cell(figures.parts(year).get("4_notices"), "TOTAL", AnnualFigures.NOTICES)).isEqualTo("0");
	}

	@Test
	void countsArrivingAtOnceAreAllKept() throws Exception {
		List<Callable<Void>> counts = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			counts.add(() -> {
				stats.notice(ReportCategory.SPAM);
				return null;
			});
		}
		ExecutorService pool = Executors.newFixedThreadPool(20);
		try {
			for (Future<Void> done : pool.invokeAll(counts)) {
				done.get();
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(mongo.findOne(Query.query(Criteria.where("year").is(year).and("metric").is(StatMetric.NOTICE)
				.and("key").is("SPAM")), ModerationStat.class).getCount()).isEqualTo(40);
	}

	private static String cell(List<List<String>> part, String key, String column) {
		int index = AnnualFigures.column(part, column);
		return part.stream().filter(row -> row.size() > 3 && row.get(3).strip().equals(key)).findFirst()
				.orElseThrow().get(index);
	}

	private static String indicator(List<List<String>> part, String indicator, String scope) {
		int value = AnnualFigures.column(part, "Value");
		return part.stream().filter(row -> row.get(4).strip().equals(indicator) && row.get(5).strip().equals(scope))
				.findFirst().orElseThrow().get(value);
	}
}
