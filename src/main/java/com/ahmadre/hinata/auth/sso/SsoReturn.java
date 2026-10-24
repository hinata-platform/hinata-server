package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.auth.SignInRefusal;
import com.ahmadre.hinata.config.HinataProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The way back from an SSO sign-in into the app, whatever its outcome. The web app, which started
 * the flow through {@code /api/v1/auth/sso/start?return=…}, gets its own origin; a native app its
 * deep-link scheme. Either way the answer is recorded for a duplicate of the same callback, which
 * must get this answer rather than an error of its own.
 */
@Component
@RequiredArgsConstructor
public class SsoReturn {

	private final HinataProperties properties;
	private final SsoCallbackReplay replay;

	/** Into the app with the single-use handoff code that redeems the token pair. */
	public void signedIn(HttpServletRequest request, HttpServletResponse response, String code)
			throws IOException {
		String query = "?code=" + encode(code);
		send(request, response, "/auth-callback" + query, "auth-callback" + query);
	}

	/** Back to the sign-in with what went wrong at the provider or in the flow. */
	public void failed(HttpServletRequest request, HttpServletResponse response, String error)
			throws IOException {
		send(request, response, "/login?ssoError=" + encode(error), "auth-callback?error=" + encode(error));
	}

	/**
	 * Back to the sign-in after Hinata refused a sign-in the provider accepted (HIN-179). Only the
	 * reason travels, a code from a fixed list that the apps translate themselves: a sentence in
	 * the address would be text any web page could put on the sign-in screen by linking to it.
	 */
	public void refused(HttpServletRequest request, HttpServletResponse response, SignInRefusal refusal)
			throws IOException {
		failed(request, response, refusal.reason());
	}

	private void send(HttpServletRequest request, HttpServletResponse response, String webPathAndQuery,
			String nativePathAndQuery) throws IOException {
		String webOrigin = SsoController.consumeReturnOrigin(request, response,
				properties.getCors().getAllowedOrigins());
		SsoController.clearInvite(request, response);
		SsoController.clearChallenge(request, response);
		String target = webOrigin != null
				? webOrigin + webPathAndQuery
				: properties.getApp().getCallbackScheme() + "://" + nativePathAndQuery;
		replay.record(request, target);
		response.sendRedirect(target);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
