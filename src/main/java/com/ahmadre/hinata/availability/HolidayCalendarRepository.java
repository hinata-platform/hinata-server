package com.ahmadre.hinata.availability;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface HolidayCalendarRepository extends MongoRepository<HolidayCalendar, String> {
}
