package com.ahmadre.hinata.timeoff;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TimeOffTypeRepository extends MongoRepository<TimeOffType, String> {

	/**
	 * How the catalogue reads everywhere: by name, then by key.
	 *
	 * <p>The key breaks the tie rather than the id, so two types an operator called the same thing
	 * keep a stable order between reads — a list that reshuffles itself is a list nobody trusts.
	 */
	Sort BY_NAME = Sort.by(Sort.Order.asc("name"), Sort.Order.asc("key"));

	Optional<TimeOffType> findByKey(String key);

	Optional<TimeOffType> findBySystemKey(String systemKey);

	List<TimeOffType> findByActiveNot(boolean inactive, Sort sort);
}
