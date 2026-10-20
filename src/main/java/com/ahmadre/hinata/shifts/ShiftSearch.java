package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.search.SearchCategory;
import com.ahmadre.hinata.search.SearchContext;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shift planning in the global search (HIN-165): events by title and place, shifts by title.
 *
 * <p>Asked only while the module is on ({@link ShiftSettings#enabled()}). Who sees an event is
 * {@link ShiftAccess}'s rule, a direct member or a team grant of its project; that is the project
 * set the search hands over, and it goes into both queries as {@code projectId $in} on the
 * {@code project_start} indexes. Three reads at most: events, shifts, and the titles of the events
 * the found shifts belong to.
 */
@Component
@RequiredArgsConstructor
class ShiftSearch implements SearchSource {

	private final ShiftSettings settings;
	private final MongoTemplate mongo;
	private final Clock clock;

	@Override
	public SearchCategory category() {
		return SearchCategory.SHIFTS;
	}

	@Override
	public boolean enabled() {
		return settings.enabled();
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		if (context.projectIds().isEmpty()) return List.of();
		Query events = Query.query(new Criteria().andOperator(inReach(context),
						new Criteria().orOperator(context.contains("title"), context.contains("location"))))
				.with(Sort.by(Sort.Direction.DESC, "startsAt", "_id")).limit(cap);
		List<SearchHit> hits = new ArrayList<>();
		mongo.find(events, ShiftEvent.class).forEach(event -> hits.add(event(event, context)));

		// Asked even when the events filled the cap, so the reads do not depend on the data.
		Query shifts = Query.query(new Criteria().andOperator(inReach(context),
						Criteria.where("deleted").ne(true), context.contains("title")))
				.with(Sort.by(Sort.Direction.DESC, "startsAt", "_id")).limit(cap);
		List<Shift> found = mongo.find(shifts, Shift.class);
		Map<String, ShiftEvent> owners = events(found);
		for (Shift shift : found) {
			ShiftEvent owner = owners.get(shift.getEventId());
			if (owner != null) hits.add(shift(shift, owner, context));
		}
		return hits.size() > cap ? hits.subList(0, cap) : hits;
	}

	/** The next events of the caller's projects, soonest first. */
	@Override
	public List<SearchHit> suggest(SearchContext context, int limit) {
		if (context.projectIds().isEmpty()) return List.of();
		Query next = Query.query(new Criteria().andOperator(inReach(context),
						Criteria.where("endsAt").gte(clock.instant())))
				.with(Sort.by(Sort.Direction.ASC, "startsAt", "_id")).limit(limit);
		return mongo.find(next, ShiftEvent.class).stream().map(event -> event(event, context)).toList();
	}

	private static Criteria inReach(SearchContext context) {
		return Criteria.where("projectId").in(context.projectIds());
	}

	private Map<String, ShiftEvent> events(List<Shift> shifts) {
		Set<String> ids = new LinkedHashSet<>();
		shifts.forEach(shift -> ids.add(shift.getEventId()));
		if (ids.isEmpty()) return Map.of();
		Query query = Query.query(Criteria.where("_id").in(ids));
		query.fields().include("title", "location", "projectId", "timezone");
		Map<String, ShiftEvent> byId = new HashMap<>();
		mongo.find(query, ShiftEvent.class).forEach(event -> byId.put(event.getId(), event));
		return byId;
	}

	private static SearchHit event(ShiftEvent event, SearchContext context) {
		Project project = context.project(event.getProjectId());
		return SearchHit.builder()
				.category(SearchCategory.SHIFTS.name())
				.kind("EVENT")
				.id(event.getId())
				.route("/shifts/events/" + event.getId())
				.title(event.getTitle())
				.location(event.getLocation())
				.startsAt(event.getStartsAt())
				.endsAt(event.getEndsAt())
				.cancelled(Boolean.TRUE.equals(event.getCancelled()) ? Boolean.TRUE : null)
				.projectId(event.getProjectId())
				.projectName(project == null ? null : project.getName())
				.build();
	}

	private static SearchHit shift(Shift shift, ShiftEvent owner, SearchContext context) {
		Project project = context.project(shift.getProjectId());
		String title = shift.getTitle() == null || shift.getTitle().isBlank() ? owner.getTitle() : shift.getTitle();
		return SearchHit.builder()
				.category(SearchCategory.SHIFTS.name())
				.kind("SHIFT")
				.id(shift.getId())
				// The event's sheet, opened on this shift.
				.route("/shifts/events/" + owner.getId() + "?shift=" + shift.getId())
				.title(title)
				.subtitle(owner.getTitle())
				.location(owner.getLocation())
				.startsAt(shift.getStartsAt())
				.endsAt(shift.getEndsAt())
				.cancelled(Boolean.TRUE.equals(shift.getCancelled()) ? Boolean.TRUE : null)
				.projectId(shift.getProjectId())
				.projectName(project == null ? null : project.getName())
				.build();
	}
}
