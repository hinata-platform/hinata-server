package com.ahmadre.hinata.availability;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;

public interface TimeOffRepository extends MongoRepository<TimeOff, String> {

	long deleteByUserId(String userId);

	/**
	 * The absences of [userId] that touch [from]–[to], both ends included.
	 *
	 * <p>Two open ranges rather than containment: an absence overlaps a span when it ends no
	 * earlier than the span starts and starts no later than it ends. Written in the order of the
	 * {@code user_to_from} index, so it is a range read rather than a walk of everything the person
	 * has ever been away for.
	 */
	List<TimeOff> findByUserIdAndToGreaterThanEqualAndFromLessThanEqual(String userId, LocalDate from, LocalDate to);
}
