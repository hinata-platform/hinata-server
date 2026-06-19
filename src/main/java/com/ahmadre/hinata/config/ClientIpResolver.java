package com.ahmadre.hinata.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the real client IP in a spoofing-resistant way (OWASP A07).
 *
 * <p>{@code X-Forwarded-For} is attacker-controlled, so it is only honoured when
 * the direct peer is a configured trusted proxy ({@code hinata.security.trusted-proxies}).
 * In that case we walk the header from right to left and return the first hop
 * that is <em>not</em> a trusted proxy — i.e. the closest untrusted client —
 * instead of the left-most value a client can freely set. With no trusted
 * proxies configured (the default) the header is ignored and the socket address
 * is used.
 */
@Component
public class ClientIpResolver {

	private final List<IpAddressMatcher> trustedProxies;

	public ClientIpResolver(HinataProperties properties) {
		this.trustedProxies = properties.getSecurity().getTrustedProxies().stream()
				.map(IpAddressMatcher::new)
				.toList();
	}

	public String resolve(HttpServletRequest request) {
		String remoteAddr = request.getRemoteAddr();
		if (trustedProxies.isEmpty() || !isTrusted(remoteAddr)) {
			// No proxy in front (or the direct peer is not a trusted one):
			// never trust the header.
			return remoteAddr;
		}
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded == null || forwarded.isBlank()) {
			return remoteAddr;
		}
		String[] hops = forwarded.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].trim();
			if (!hop.isEmpty() && !isTrusted(hop)) {
				return hop;
			}
		}
		return remoteAddr;
	}

	private boolean isTrusted(String ip) {
		for (IpAddressMatcher matcher : trustedProxies) {
			try {
				if (matcher.matches(ip)) {
					return true;
				}
			}
			catch (IllegalArgumentException ignored) {
				// Not a comparable IP literal (e.g. malformed header value).
			}
		}
		return false;
	}
}
