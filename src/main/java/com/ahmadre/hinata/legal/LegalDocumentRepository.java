package com.ahmadre.hinata.legal;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface LegalDocumentRepository extends MongoRepository<LegalDocument, String> {
}
