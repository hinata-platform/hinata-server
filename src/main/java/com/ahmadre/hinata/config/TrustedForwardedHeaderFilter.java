package com.ahmadre.hinata.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Applies a reverse proxy's forwarded headers, but takes the client's address from them only when
 * the request came from a trusted proxy ({@code hinata.security.trusted-proxies}).
 *
 * <p>Spring's {@link ForwardedHeaderFilter} believes every {@code X-Forwarded-For} and makes its
 * left-most value the remote address: a value any client sets freely, so each request could claim
 * a new address and every limit per address, sign-in and reports without an account among them,
 * would count nothing. This one is that filter, but the header reaches it only from a trusted
 * proxy, cut down to the closest hop that is not one; from anybody else the address stays the
 * socket's. Scheme, host and port pass on as before. Being a {@link ForwardedHeaderFilter} also
 * keeps out the one springdoc's Scalar page registers when none is there.
 *
 * <p>When a request brings the header from a peer that is not trusted, which is what a proxy in
 * front looks like before it is entered, the moment is kept: the admin area says that every
 * request then counts as the proxy's address.
 */
@Slf4j
public class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter {

	static final String X_FORWARDED_FOR = "X-Forwarded-For";
	static final String FORWARDED = "Forwarded";

	/** A {@code for=} element of RFC 7239 and the separator that follows it. */
	private static final Pattern FOR_ELEMENT = Pattern.compile("(?i)for\\s*=\\s*(\"[^\"]*\"|[^;,]*)\\s*;?\\s*");

	private final List<IpAddressMatcher> trustedProxies;
	private volatile Instant ignoredAt;

	public TrustedForwardedHeaderFilter(List<String> trustedProxies) {
		this.trustedProxies = trustedProxies.stream().map(String::strip).filter(cidr -> !cidr.isEmpty())
				.map(IpAddressMatcher::new).toList();
	}

	/** When a request last brought {@code X-Forwarded-For} from a peer that is not a trusted proxy. */
	public Instant ignoredAt() {
		return ignoredAt;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		super.doFilterInternal(new Guarded(request, clientBehindProxies(request)), response, chain);
	}

	/**
	 * The client's address behind trusted proxies: the right-most hop of {@code X-Forwarded-For}
	 * that is not one of them, because every hop left of it is whatever that client wrote. Null when
	 * the header does not count.
	 */
	String clientBehindProxies(HttpServletRequest request) {
		String header = request.getHeader(X_FORWARDED_FOR);
		if (header == null || header.isBlank()) {
			return null;
		}
		if (!isTrusted(request.getRemoteAddr())) {
			if (ignoredAt == null) {
				log.warn("X-Forwarded-For from {} is ignored: it is not a trusted proxy "
						+ "(HINATA_TRUSTED_PROXIES), so its clients count as its address", request.getRemoteAddr());
			}
			ignoredAt = Instant.now();
			return null;
		}
		String[] hops = header.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].strip();
			if (!hop.isEmpty() && !isTrusted(hop)) {
				return hop;
			}
		}
		return null;
	}

	private boolean isTrusted(String address) {
		for (IpAddressMatcher matcher : trustedProxies) {
			try {
				if (matcher.matches(address)) {
					return true;
				}
			}
			catch (IllegalArgumentException notAnAddress) {
				// A header value that is no IP literal is no trusted proxy.
			}
		}
		return false;
	}

	/** [header] without its {@code for=} elements; null when nothing else was in it. */
	static String withoutFor(String header) {
		if (header == null) {
			return null;
		}
		List<String> kept = new ArrayList<>();
		for (String element : header.split(",")) {
			String rest = FOR_ELEMENT.matcher(element).replaceAll("").strip();
			while (rest.endsWith(";")) {
				rest = rest.substring(0, rest.length() - 1).strip();
			}
			if (!rest.isEmpty()) {
				kept.add(rest);
			}
		}
		return kept.isEmpty() ? null : String.join(", ", kept);
	}

	/** The request as the forwarded headers may tell it: the client's address only from trusted proxies. */
	private static final class Guarded extends HttpServletRequestWrapper {

		private final String client;

		Guarded(HttpServletRequest request, String client) {
			super(request);
			this.client = client;
		}

		@Override
		public String getHeader(String name) {
			if (X_FORWARDED_FOR.equalsIgnoreCase(name)) {
				return client;
			}
			if (FORWARDED.equalsIgnoreCase(name)) {
				return withoutFor(super.getHeader(name));
			}
			return super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			if (X_FORWARDED_FOR.equalsIgnoreCase(name)) {
				return client == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(client));
			}
			if (FORWARDED.equalsIgnoreCase(name)) {
				List<String> kept = new ArrayList<>();
				for (String value : Collections.list(super.getHeaders(name))) {
					String rest = withoutFor(value);
					if (rest != null) {
						kept.add(rest);
					}
				}
				return Collections.enumeration(kept);
			}
			return super.getHeaders(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			List<String> names = new ArrayList<>();
			for (String name : Collections.list(super.getHeaderNames())) {
				if (X_FORWARDED_FOR.equalsIgnoreCase(name) ? client != null
						: !FORWARDED.equalsIgnoreCase(name) || getHeaders(name).hasMoreElements()) {
					names.add(name);
				}
			}
			return Collections.enumeration(names);
		}
	}
}
