package com.ahmadre.hinata.availability;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkingScheduleRepository extends MongoRepository<WorkingSchedule, String> {

	/** A person's patterns, newest first. */
	List<WorkingSchedule> findByUserIdOrderByValidFromDesc(String userId, Pageable page);

	/** The patterns that can apply up to [day], newest first. */
	List<WorkingSchedule> findByUserIdAndValidFromLessThanEqualOrderByValidFromDesc(String userId,
			LocalDate day, Pageable page);

	Optional<WorkingSchedule> findByUserIdAndValidFrom(String userId, LocalDate validFrom);

	long countByUserId(String userId);

	long deleteByUserId(String userId);
}
