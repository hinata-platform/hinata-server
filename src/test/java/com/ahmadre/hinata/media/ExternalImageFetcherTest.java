package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The proxy's refusal of addresses that are not on the public internet. Every URL
 * names its address literally, so nothing is resolved and nothing connects.
 */
class ExternalImageFetcherTest {

	private final ExternalImageFetcher fetcher = new ExternalImageFetcher();

	@ParameterizedTest
	@ValueSource(strings = {
			"http://127.0.0.1/logo.png",
			"http://169.254.169.254/latest/meta-data/",
			"http://[::ffff:169.254.169.254]/logo.png",
			"http://[fd00::1]/logo.png",
			// The NAT64 spelling of the metadata address. The JDK's own address
			// predicates see an ordinary IPv6 address here, and so did this class
			// until it asked PublicAddresses.
			"http://[64:ff9b::a9fe:a9fe]/logo.png" })
	void refusesAnAddressThatIsNotOnThePublicInternet(String url) {
		assertThatThrownBy(() -> fetcher.fetch(url))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.media.urlNotAllowed"));
	}
}
