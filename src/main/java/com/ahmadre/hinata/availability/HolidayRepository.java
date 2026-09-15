package com.ahmadre.hinata.availability;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface HolidayRepository extends MongoRepository<Holiday, String> {

	long deleteByCalendarId(String calendarId);
}
