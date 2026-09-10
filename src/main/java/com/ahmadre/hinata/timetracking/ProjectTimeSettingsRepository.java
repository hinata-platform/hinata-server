package com.ahmadre.hinata.timetracking;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectTimeSettingsRepository
		extends MongoRepository<ProjectTimeSettings, String> {

	Optional<ProjectTimeSettings> findByProjectId(String projectId);

	/**
	 * Several projects' rows in one query.
	 *
	 * <p>For the readers that ask about a set of projects rather than one: the
	 * periods route needs each project's rhythm and whether it is handed in at all,
	 * and asking per project turned one request into {@code periods × projects}
	 * round trips.
	 */
	List<ProjectTimeSettings> findByProjectIdIn(Collection<String> projectIds);

	/**
	 * Whether <em>any</em> project has closed its own books.
	 *
	 * <p>Asked once and cached, so the write gate can return early on the instance
	 * that has configured nothing — which is every instance, until somebody sets a
	 * date. Without it every write to a time entry pays an indexed read for an
	 * answer that is always null.
	 */
	boolean existsByLockBeforeNotNull();
}
