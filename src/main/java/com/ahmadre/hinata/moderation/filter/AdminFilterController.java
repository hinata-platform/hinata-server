package com.ahmadre.hinata.moderation.filter;

import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.richtext.TextField;
import com.ahmadre.hinata.richtext.TextVerdict;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * "Test a text" in Admin → Moderation (HIN-176): what the text check would say about a text, with
 * the settings as they are, and why. The text is neither stored nor logged nor counted.
 * Administrators only, by the path.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/moderation/filter")
@RequiredArgsConstructor
class AdminFilterController {

	private final LexiconTextGate gate;

	record TestRequest(@NotNull @Size(max = 10_000) String text, TextField field) {
	}

	/** One find: the term, its category, what it weighs where it stands, and where that is. */
	record Found(String term, ReportCategory category, int weight, boolean technical, boolean own) {
	}

	/**
	 * The verdict the settings give, everything found whatever the switches say, and the exception
	 * words in the text that a rule starts with, such as "kill" in "kill -9".
	 */
	record TestResult(TextVerdict.Verdict verdict, ReportCategory category, List<String> terms, List<Found> found,
			Set<String> exceptions) {
	}

	@Operation(summary = "What the text check says about a text, and why; nothing is stored")
	@PostMapping("/test")
	TestResult test(@RequestBody @Valid TestRequest request) {
		TextField field = request.field() == null ? TextField.COMMENT : request.field();
		TextVerdict verdict = gate.evaluate(request.text(), field, null);
		LexiconTextGate.Judged judged = gate.judge(request.text(), field);
		List<Found> found = judged.finds().stream().map(find -> new Found(find.hit().term(), find.hit().category(),
				find.weight(), find.technical(), find.hit().own())).toList();
		return new TestResult(verdict.verdict(), verdict.category(), verdict.terms(), found, judged.exempt());
	}
}
