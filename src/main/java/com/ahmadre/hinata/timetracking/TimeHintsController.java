package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The Working Hours Act hints and the late-entry hint, for the caller's own entries
 * and nobody else's. See {@link WorkingTimeHints}.
 *
 * <p>There is no parameter naming a person, and there never may be one: the moment
 * a lead could ask this for a member it would be an evaluation of that member's
 * working behaviour (§ 87 Abs. 1 Nr. 6 BetrVG, R7). Nothing is stored, counted or
 * sent anywhere; the answer exists for as long as the response does.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/hints")
@RequiredArgsConstructor
public class TimeHintsController {

	private final TimeHintsService hints;
	private final CurrentUser currentUser;

	public record HintResponse(WorkingTimeHints.Kind kind, LocalDate date, String entryId,
			Integer minutes, Integer restMinutes, Integer daysLate) {
	}

	public record HintsResponse(LocalDate from, LocalDate to, List<HintResponse> hints) {
	}

	@GetMapping
	public HintsResponse hints(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		User user = currentUser.require();
		List<HintResponse> found = hints.hintsFor(from, to, user).stream()
				.map(hint -> new HintResponse(hint.kind(), hint.date(), hint.entryId(),
						hint.minutes(), hint.restMinutes(), hint.daysLate()))
				.toList();
		return new HintsResponse(from, to, found);
	}
}
