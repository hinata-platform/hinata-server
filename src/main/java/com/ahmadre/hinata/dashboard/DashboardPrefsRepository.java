package com.ahmadre.hinata.dashboard;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DashboardPrefsRepository extends MongoRepository<DashboardPrefs, String> {
}
