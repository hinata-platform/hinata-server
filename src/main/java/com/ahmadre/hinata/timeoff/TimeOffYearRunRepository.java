package com.ahmadre.hinata.timeoff;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

interface TimeOffYearRunRepository extends MongoRepository<TimeOffYearRunRecord, String> {

	Optional<TimeOffYearRunRecord> findFirstByOrderByIdDesc();
}
