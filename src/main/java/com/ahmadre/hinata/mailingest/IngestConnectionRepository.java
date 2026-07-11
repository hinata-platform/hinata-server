package com.ahmadre.hinata.mailingest;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IngestConnectionRepository extends MongoRepository<IngestConnection, String> {

	List<IngestConnection> findByEnabledTrue();
}
