package com.ahmadre.hinata.availability;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface HolidayCalendarRepository extends MongoRepository<HolidayCalendar, String> {

	Optional<HolidayCalendar> findFirstByDefaultCalendarTrue();
}
