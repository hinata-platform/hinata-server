package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.common.TestMongo;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The single-field userId index of an older server goes once, and only it. */
@Testcontainers(disabledWithoutDocker = true)
class DeviceTokenIndexMigrationTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static MongoClient client;
	private static MongoTemplate mongo;

	@BeforeAll
	static void connect() {
		client = MongoClients.create(MONGO.getReplicaSetUrl());
		mongo = new MongoTemplate(client, "hinata");
	}

	@AfterAll
	static void close() {
		client.close();
	}

	@Test
	void theRetiredIndexIsDroppedOnceAndTheCompoundOneStays() {
		var collection = mongo.getCollection("device_tokens");
		collection.createIndex(new Document("userId", 1), new com.mongodb.client.model.IndexOptions().name("userId"));
		collection.createIndex(new Document("userId", 1).append("kind", 1),
				new com.mongodb.client.model.IndexOptions().name("user_kind"));
		DeviceTokenIndexMigration migration = new DeviceTokenIndexMigration(mongo);

		assertThat(migration.drop()).isTrue();
		assertThat(migration.drop()).isFalse();

		List<String> names = new ArrayList<>();
		collection.listIndexes().forEach(index -> names.add(index.getString("name")));
		assertThat(names).contains("user_kind").doesNotContain("userId");
	}
}
