package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.AvailabilityPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tells availability whether leads see their members' absences, exactly when they see their
 * members' entries, and through which projects: the ones the person recorded time on.
 *
 * <p>One switch for both, because they answer the same question for a works council, whether a
 * lead may see what a particular person is doing, and an instance where a lead saw the absences
 * but not the hours, or the reverse, would need two agreements for one decision. With the module
 * off there is nothing to see.
 *
 * <p>The projects follow the entries for the same reason a lead's view of entries does: a lead sees
 * what was booked on the projects they lead, and a project somebody merely added a person to holds
 * nothing of theirs.
 */
@Component
@RequiredArgsConstructor
public class TimeAvailabilityPolicy implements AvailabilityPolicy {

	private final TimeTrackingSettings settings;
	private final MongoTemplate mongo;

	@Override
	public boolean leadsSeeMemberAbsences() {
		return settings.advancedEnabled() && settings.leadsSeeMemberEntries();
	}

	@Override
	public Set<String> projectsWorkedOn(String userId, LocalDate since) {
		// Answered from user_project_date.
		Query query = Query.query(Criteria.where("userId").is(userId).and("date").gte(since));
		return mongo.findDistinct(query, "projectId", WorkItem.class, String.class).stream()
				.filter(Objects::nonNull)
				.collect(Collectors.toUnmodifiableSet());
	}
}
