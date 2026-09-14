package com.ahmadre.hinata.common;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reader's own rule. Caps and encodings are tested through the fetchers, against a server. */
class CappedBodyTest {

	@Test
	void refusesARequestThatLeftTheEncodingToOkHttp() {
		Response response = new Response.Builder()
				.request(new Request.Builder().url("https://images.test/logo.png").build())
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(ResponseBody.create(new byte[] { 1 }, null))
				.build();

		assertThatThrownBy(() -> CappedBody.read(response, 10)).isInstanceOf(IllegalArgumentException.class);
	}
}
