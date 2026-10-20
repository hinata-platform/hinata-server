package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * The routes of series (HIN-161), behind {@link ShiftPlanningGate}: create one, preview a draft,
 * read one. Changing and deleting a series runs through its occurrences with {@code scope=future}
 * on the event and shift routes of {@link ShiftController}.
 */
@Tag(name = "Shift planning")
@RestController
@RequiredArgsConstructor
public class ShiftSeriesController {

	/** How many upcoming occurrences a series and a preview show. */
	static final int UPCOMING = 5;

	private final ShiftSeriesService series;
	private final ShiftSeriesLabels labels;
	private final ShiftAccess access;
	private final CurrentUser currentUser;

	/** One template of a series, in wall-clock times of its zone. */
	public record TemplateResponse(String key, String title, int dayOffset, LocalTime start, LocalTime end,
			int requiredHelpers, Integer maxHelpers) {

		static TemplateResponse from(ShiftTemplate template) {
			return new TemplateResponse(template.getKey(), template.getTitle(), template.offset(), template.getStart(),
					template.getEnd(), template.required(), template.getMaxHelpers());
		}
	}

	/** One upcoming occurrence: its day, and when it starts and ends, also on the zone's wall clock. */
	public record OccurrenceResponse(LocalDate date, Instant startsAt, Instant endsAt, String startsLocal,
			String endsLocal) {

		static OccurrenceResponse from(ShiftSeriesService.Upcoming upcoming, String zone) {
			return new OccurrenceResponse(upcoming.date(), upcoming.startsAt(), upcoming.endsAt(),
					ShiftRules.local(upcoming.startsAt(), zone), ShiftRules.local(upcoming.endsAt(), zone));
		}
	}

	/**
	 * A series. {@code recurrenceLabel} is the rule in the reader's words, {@code next} the next five
	 * occurrences from today on, {@code occurrencesCreated} how many occurrences creating it brought
	 * (only in the answer to the creation, null otherwise). {@code firstEventId} is the first
	 * occurrence from today on that is not cancelled, the event to open; null when there is none.
	 */
	public record SeriesResponse(String id, String projectId, String title, String description, String location,
			String timezone, LocalDateTime firstStart, int durationMinutes, String rrule, String recurrenceLabel,
			List<LocalDate> exdates, List<TemplateResponse> shifts, boolean selfAssignable, LocalDate materializedUntil,
			boolean finished, boolean canPlan, Instant createdAt, List<OccurrenceResponse> next,
			Integer occurrencesCreated, String firstEventId) {
	}

	/** What a draft would make: its label and its next five occurrences. Nothing is saved. */
	public record PreviewResponse(String recurrenceLabel, List<OccurrenceResponse> next) {
	}

	@PostMapping("/api/v1/projects/{projectId}/shifts/series")
	@ResponseStatus(HttpStatus.CREATED)
	public SeriesResponse create(@PathVariable String projectId, @RequestBody ShiftSeriesService.SeriesDraft draft) {
		User caller = currentUser.require();
		ShiftSeriesService.Created created = series.create(caller, projectId, draft);
		return response(caller, created.series(), true, created.occurrences());
	}

	@PostMapping("/api/v1/projects/{projectId}/shifts/series:preview")
	public PreviewResponse preview(@PathVariable String projectId, @RequestBody ShiftSeriesService.SeriesDraft draft) {
		User caller = currentUser.require();
		ShiftSeries drafted = series.preview(caller, projectId, draft);
		return new PreviewResponse(labels.of(caller, drafted), next(drafted));
	}

	@GetMapping("/api/v1/shifts/series/{id}")
	public SeriesResponse get(@PathVariable String id) {
		User caller = currentUser.require();
		ShiftAccess.Scoped<ShiftSeries> scoped = series.visible(caller, id);
		return response(caller, scoped.item(), access.canPlan(scoped.project(), caller), null);
	}

	private SeriesResponse response(User caller, ShiftSeries found, boolean canPlan, Integer created) {
		return new SeriesResponse(found.getId(), found.getProjectId(), found.getTitle(), found.getDescription(),
				found.getLocation(), found.getTimezone(), found.getFirstStart(), found.duration(), found.getRrule(),
				labels.of(caller, found), found.getExdates() == null ? List.of() : found.getExdates(),
				found.getShifts() == null ? List.of() : found.getShifts().stream().map(TemplateResponse::from).toList(),
				found.isSelfAssignable(), found.getMaterializedUntil(), found.isFinished(), canPlan,
				found.getCreatedAt(), next(found), created, series.firstEventId(found));
	}

	private List<OccurrenceResponse> next(ShiftSeries found) {
		return series.upcoming(found, UPCOMING).stream()
				.map(upcoming -> OccurrenceResponse.from(upcoming, found.getTimezone())).toList();
	}
}
