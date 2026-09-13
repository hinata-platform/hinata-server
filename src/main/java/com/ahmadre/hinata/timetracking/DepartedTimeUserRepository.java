package com.ahmadre.hinata.timetracking;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DepartedTimeUserRepository extends MongoRepository<DepartedTimeUser, String> {
}
