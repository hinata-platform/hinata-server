package com.ahmadre.hinata.availability;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface TimeOffRepository extends MongoRepository<TimeOff, String> {

	long deleteByUserId(String userId);
}
