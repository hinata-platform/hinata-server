package com.ahmadre.hinata.block;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Blocking itself (HIN-26, HIN-168): one row per pair, idempotent both ways, bounded per
 * person, read back a page at a time, and gone with either account.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class BlockServiceIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private BlockService blocks;
	@Autowired
	private ApplicationEventPublisher events;
	@Autowired
	private com.ahmadre.hinata.moderation.ModerationPersonalData personalData;

	private User anna;
	private User ben;
	private User cleo;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "user_blocks")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		anna = user("anna");
		ben = user("ben");
		cleo = user("cleo");
	}

	@Test
	@DisplayName("blocking twice is one block, lifting it twice is no error, and only the blocker's view changes")
	void blockingIsIdempotentAndOneSided() {
		blocks.block(anna, ben.getId());
		blocks.block(anna, ben.getId());

		assertThat(mongo.getCollection("user_blocks").countDocuments()).isEqualTo(1);
		assertThat(blocks.blocks(anna.getId(), ben.getId())).isTrue();
		assertThat(blocks.blocks(ben.getId(), anna.getId())).isFalse();
		assertThat(blocks.blockersOf(ben.getId())).containsExactly(anna.getId());
		assertThat(blocks.blockersOf(anna.getId())).isEmpty();

		blocks.unblock(anna, ben.getId());
		blocks.unblock(anna, ben.getId());
		assertThat(blocks.blocks(anna.getId(), ben.getId())).isFalse();
		assertThat(blocks.blockersOf(ben.getId())).isEmpty();
	}

	@Test
	@DisplayName("nobody blocks themselves or an account that does not exist")
	void selfAndUnknownAreRefused() {
		assertThatThrownBy(() -> blocks.block(anna, anna.getId()))
				.isInstanceOf(ApiException.class).hasMessageContaining("error.block.self");
		assertThatThrownBy(() -> blocks.block(anna, "0123456789abcdef01234567"))
				.isInstanceOf(ApiException.class).hasMessageContaining("error.notFound");
		assertThat(mongo.getCollection("user_blocks").countDocuments()).isZero();
	}

	@Test
	@DisplayName("a person blocks at most a thousand others, so the list of ids stays small")
	void blockingIsBounded() {
		List<Document> rows = new ArrayList<>();
		Instant at = Instant.now();
		for (int i = 0; i < BlockService.MAX_BLOCKS; i++) {
			rows.add(new Document("blockerId", anna.getId()).append("blockedId", "someone-" + i)
					.append("createdAt", at));
		}
		mongo.getCollection("user_blocks").insertMany(rows);

		assertThatThrownBy(() -> blocks.block(anna, ben.getId()))
				.isInstanceOf(ApiException.class).hasMessageContaining("error.block.limit");
		// Lifting one makes room again.
		blocks.unblock(anna, "someone-0");
		blocks.block(anna, ben.getId());
		assertThat(blocks.blocks(anna.getId(), ben.getId())).isTrue();
	}

	@Test
	@DisplayName("the list is the newest first, a page at a time, with the people's names")
	void theListIsPagedNewestFirst() {
		blocks.block(anna, ben.getId());
		blocks.block(anna, cleo.getId());

		BlockService.BlockPage first = blocks.list(anna, 0, 1);
		BlockService.BlockPage second = blocks.list(anna, 1, 1);

		assertThat(first.total()).isEqualTo(2);
		assertThat(first.items()).singleElement().satisfies(person -> {
			assertThat(person.id()).isEqualTo(cleo.getId());
			assertThat(person.displayName()).isEqualTo("cleo");
			assertThat(person.blockedAt()).isNotNull();
		});
		assertThat(second.items()).extracting(BlockService.BlockedPerson::id).containsExactly(ben.getId());
		assertThat(blocks.blockedIds(anna)).containsExactlyInAnyOrder(ben.getId(), cleo.getId());
		// A page is never larger than a hundred, whatever was asked.
		assertThat(blocks.list(anna, 0, 5000).items()).hasSize(2);
	}

	@Test
	@DisplayName("deleting an account removes its blocks in both directions")
	void deletingAnAccountRemovesItsBlocks() {
		blocks.block(anna, ben.getId());
		blocks.block(ben, cleo.getId());
		blocks.block(cleo, anna.getId());

		events.publishEvent(new UserService.UserDeletedEvent(ben.getId()));

		assertThat(blocks.blockersOf(ben.getId())).isEmpty();
		assertThat(blocks.blocks(ben.getId(), cleo.getId())).isFalse();
		assertThat(blocks.blocks(cleo.getId(), anna.getId())).isTrue();
	}

	@Test
	@DisplayName("one's own data export names whom one blocks, never who blocks oneself")
	void theExportNamesOnlyOnesOwnBlocks() {
		blocks.block(anna, ben.getId());

		assertThat(personalData.data(anna)).asString().contains(ben.getId()).contains("ben");
		assertThat(personalData.tables(anna, java.util.Locale.GERMAN)).singleElement()
				.satisfies(table -> assertThat(table.rows()).singleElement()
						.satisfies(row -> assertThat(row).contains("ben")));
		assertThat(personalData.data(ben)).asString().doesNotContain(anna.getId());
		assertThat(personalData.tables(ben, java.util.Locale.GERMAN)).isEmpty();
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@example.org").displayName(name)
				.roles(Set.of(Role.MEMBER)).active(true).build());
	}
}
