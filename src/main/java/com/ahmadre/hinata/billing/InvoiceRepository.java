package com.ahmadre.hinata.billing;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface InvoiceRepository extends MongoRepository<Invoice, String> {

	boolean existsByProjectIdAndStatus(String projectId, Invoice.Status status);

	void deleteByProjectIdAndStatus(String projectId, Invoice.Status status);
}
