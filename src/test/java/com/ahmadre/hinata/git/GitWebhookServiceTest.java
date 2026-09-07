package com.ahmadre.hinata.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Bitbucket names a commit author as one git-style string; the address has to come out of it. */
class GitWebhookServiceTest {

	@Test
	void theAddressComesOutOfAGitStyleAuthorString() {
		assertThat(GitWebhookService.emailFromRaw("Ada Lovelace <ada@example.org>")).isEqualTo("ada@example.org");
		assertThat(GitWebhookService.emailFromRaw("<ada@example.org>")).isEqualTo("ada@example.org");
		assertThat(GitWebhookService.emailFromRaw("ada@example.org")).isEqualTo("ada@example.org");
	}

	@Test
	void anythingThatIsNotAnAddressIsNull() {
		assertThat(GitWebhookService.emailFromRaw("Ada Lovelace")).isNull();
		assertThat(GitWebhookService.emailFromRaw("Ada <not-an-address>")).isNull();
		assertThat(GitWebhookService.emailFromRaw("")).isNull();
		assertThat(GitWebhookService.emailFromRaw(null)).isNull();
	}
}
