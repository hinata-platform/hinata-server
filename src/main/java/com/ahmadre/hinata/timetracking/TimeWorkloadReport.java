package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.AvailabilityAccess;
import com.ahmadre.hinata.availability.CapacityService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Booked time against capacity, per person (HIN-93) — a reading of individual people, so it
 * exists only while an operator switched {@code workloadReportsEnabled} on, and only for an
 * administrator or a lead while leads see their members' entries.
 *
 * <p>Nothing here ranks anybody. People come in order of name, and a row carries capacity, booked
 * time and the difference as numbers — no "too little", no colour, no sort by hours (R10). Who is
 * in the report is the capacity band's rule ({@link AvailabilityAccess#roster}): a lead sees
 * somebody who recorded time themselves on a project the lead leads, never somebody the lead
 * merely added. Booked time is what the reader may see of that person's entries: for a lead, the
 * time on the lead's own projects, which is what the column says.
 */
@Service
@RequiredArgsConstructor
public class TimeWorkloadReport {

	private static final Collation BY_NAME = Collation.of("en").strength(Collation.ComparisonLevel.secondary());

	private final TimeTrackingSettings policy;
	private final AvailabilityAccess access;
	private final CapacityService capacity;
	private final TimeReportService reports;
	private final TimeReportScope scopes;
	private final MongoTemplate mongo;

	/**
	 * One person's row.
	 *
	 * @param bookedMinutes     what the reader may see them book in the window, rounded per entry
	 * @param differenceMinutes booked minus capacity, signed; nothing more is made of it
	 */
	public record Row(String userId, String name, String username, int scheduledMinutes, int holidayMinutes,
			int absenceMinutes, int capacityMinutes, long bookedMinutes, long differenceMinutes) {
	}

	/**
	 * @param truncated          the group is larger than one report reads
	 * @param bookedInLedProjects booked time counts only the reader's own projects (a lead)
	 */
	public record Workload(Page<Row> people, boolean truncated, boolean bookedInLedProjects) {
	}

	public Workload workload(User viewer, TimeReportFilter filter, String teamId, String projectId, int page,
			int size) {
		if (!policy.workloadReportsEnabled()) {
			throw new ApiException(HttpStatus.NOT_FOUND, AdvancedTimeTrackingGate.DISABLED_KEY);
		}
		TimeReportScope.Reach reach = scopes.of(viewer);
		if (!viewer.isAdmin() && reach.ledProjects().isEmpty()) {
			throw ApiException.forbidden("error.time.report.workloadForbidden");
		}
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, TimeReportService.PAGE_MAX));
		boolean scoped = teamId != null && !teamId.isBlank() || projectId != null && !projectId.isBlank();
		Page<Document> people;
		boolean truncated = false;
		if (viewer.isAdmin() && !scoped) {
			people = everybody(filter, pageable);
		}
		else {
			AvailabilityAccess.Roster roster = access.roster(viewer, teamId, projectId,
					AvailabilityAccess.Purpose.BAND);
			truncated = roster.truncated();
			people = byName(roster.userIds(), filter, pageable);
		}
		List<String> ids = people.getContent().stream().map(WorkItemDocuments::id).toList();
		Map<String, CapacityService.PersonTotal> capacities = capacity.totals(ids, filter.from(), filter.to());
		Map<String, Long> booked = reports.minutesByPerson(reach, filter, ids);
		List<Row> rows = new ArrayList<>(ids.size());
		for (Document person : people.getContent()) {
			String id = WorkItemDocuments.id(person);
			CapacityService.PersonTotal total = capacities.getOrDefault(id,
					new CapacityService.PersonTotal(0, 0, 0, 0));
			long minutes = booked.getOrDefault(id, 0L);
			rows.add(new Row(id, name(person), person.getString("username"), total.scheduledMinutes(),
					total.holidayMinutes(), total.absenceMinutes(), total.capacityMinutes(), minutes,
					minutes - total.capacityMinutes()));
		}
		return new Workload(new PageImpl<>(rows, pageable, people.getTotalElements()), truncated,
				!viewer.isAdmin());
	}

	/** Every active account, by name, paged in the database: an administrator without a group. */
	private Page<Document> everybody(TimeReportFilter filter, Pageable pageable) {
		Criteria criteria = Criteria.where("active").is(true);
		if (!filter.userIds().isEmpty()) {
			criteria = criteria.and("_id").in(filter.userIds());
		}
		Query query = Query.query(criteria).collation(BY_NAME)
				.with(Sort.by(Sort.Order.asc("displayName"), Sort.Order.asc("_id")));
		query.fields().include("displayName").include("username");
		List<Document> page = mongo.query(User.class).as(Document.class)
				.matching(Query.of(query).with(pageable)).all();
		long total = mongo.query(User.class).matching(Query.query(criteria)).count();
		return new PageImpl<>(page, pageable, total);
	}

	/** [ids], narrowed by the filter's people, by name — a roster is at most a thousand people. */
	private Page<Document> byName(List<String> ids, TimeReportFilter filter, Pageable pageable) {
		List<String> wanted = filter.userIds().isEmpty() ? ids
				: ids.stream().filter(Set.copyOf(filter.userIds())::contains).toList();
		Map<String, Document> named = new HashMap<>(reports.documents(User.class, wanted, "displayName", "username"));
		Collator collator = Collator.getInstance(Locale.ENGLISH);
		collator.setStrength(Collator.SECONDARY);
		List<Document> sorted = new ArrayList<>(named.values());
		sorted.sort(Comparator.comparing(TimeWorkloadReport::name, Comparator.nullsLast(collator))
				.thenComparing(WorkItemDocuments::id));
		int from = (int) Math.min(pageable.getOffset(), sorted.size());
		int to = Math.min(from + pageable.getPageSize(), sorted.size());
		return new PageImpl<>(sorted.subList(from, to), pageable, sorted.size());
	}

	private static String name(Document person) {
		String display = person.getString("displayName");
		return display != null && !display.isBlank() ? display : person.getString("username");
	}
}
