package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The authserv-id an administrator names for an ingest connection (HIN-181). */
class IngestConnectionAuthservIdTest {

	private final IngestConnectionRepository connections = mock(IngestConnectionRepository.class);
	private final IngestConnectionService service =
			new IngestConnectionService(connections, mock(SettingsService.class), mock(MongoTemplate.class));

	private static IngestConnection connection(String authservId) {
		IngestConnection connection = new IngestConnection();
		connection.setHost("imap.example.org");
		connection.setUsername("tickets@example.org");
		connection.setAuthservId(authservId);
		return connection;
	}

	@Test
	void create_storesTheAuthservIdInLowerCase() {
		when(connections.save(any())).thenAnswer(call -> call.getArgument(0));

		assertThat(service.create(connection("  MX.Asta.HN ")).getAuthservId()).isEqualTo("mx.asta.hn");
		assertThat(service.create(connection("   ")).getAuthservId()).isNull();
		assertThat(service.create(connection(null)).getAuthservId()).isNull();
	}

	@Test
	void create_refusesAnAuthservIdThatIsNoHostName() {
		for (String bad : new String[] { "mx.asta.hn; dmarc=pass", "a b", "x".repeat(256) }) {
			assertThatThrownBy(() -> service.create(connection(bad))).as(bad)
					.isInstanceOfSatisfying(ApiException.class,
							ex -> assertThat(ex.getMessageKey()).isEqualTo("error.ingest.authservIdInvalid"));
		}
	}
}
