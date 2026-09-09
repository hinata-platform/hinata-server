package com.ahmadre.hinata.timetracking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TimeTagRepository extends MongoRepository<TimeTag, String> {

	/** How the catalogue reads everywhere: alphabetical by the identity, not the display name. */
	Sort BY_NAME = Sort.by(Sort.Order.asc("normalized"));

	Optional<TimeTag> findByNormalized(String normalized);

	List<TimeTag> findByNormalizedIn(Collection<String> normalized);

	/**
	 * The picker's search: a prefix, anchored and quoted at the call site.
	 * Anchored because that is what an index can walk — a contains-search over a
	 * growing catalogue is a collection scan per keystroke.
	 */
	Page<TimeTag> findByNormalizedStartingWith(String prefix, Pageable pageable);
}
