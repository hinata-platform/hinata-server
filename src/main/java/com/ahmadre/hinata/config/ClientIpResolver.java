package com.ahmadre.hinata.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * The client's IP address for limits, sessions and the audit (OWASP A07). Which address that is,
 * the servlet filter in front decides ({@link TrustedForwardedHeaderFilter}): the socket's, or
 * behind trusted proxies the closest hop that is not one. Never a value a client chose.
 */
@Component
public class ClientIpResolver {

	public String resolve(HttpServletRequest request) {
		return request.getRemoteAddr();
	}
}
