package com.ahmadre.hinata.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Registers a {@link MongoTransactionManager} so the {@code @Transactional}
 * annotations in the service layer become real replica-set transactions
 * instead of silent no-ops (Spring Boot does <em>not</em> auto-configure one for
 * MongoDB, so without this bean every {@code @Transactional} boundary was
 * inert and multi-document mutations — e.g. sprint completion — ran
 * non-atomically).
 *
 * <p>Gated behind {@code hinata.mongodb.transactions-enabled} because Mongo
 * transactions require a replica set / mongos: production runs a replica set
 * (enabled there), while a standalone dev mongod would throw on any transaction
 * (left disabled, matching prior behaviour).
 */
@Configuration
@ConditionalOnProperty(prefix = "hinata.mongodb", name = "transactions-enabled", havingValue = "true")
public class MongoTxConfig {

	@Bean
	public MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
		return new MongoTransactionManager(dbFactory);
	}
}
