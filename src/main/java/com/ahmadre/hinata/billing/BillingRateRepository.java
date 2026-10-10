package com.ahmadre.hinata.billing;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BillingRateRepository extends MongoRepository<BillingRate, String> {

	/** One target's rates of one kind, oldest start first. */
	List<BillingRate> findByKindAndScopeAndScopeIdAndSecondaryIdOrderByValidFromAsc(BillingRate.Kind kind,
			BillingRate.Scope scope, String scopeId, String secondaryId);

	void deleteByProjectId(String projectId);
}
