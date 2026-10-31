package com.ahmadre.hinata.config;

import com.ahmadre.hinata.richtext.DocForm;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Holds the {@link DocForm} a request asks for while its response is written (HIN-181), and
 * tells every cache on the way that an API response differs by it ({@code Vary}).
 */
@Component
public class DocFormFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getRequestURI().startsWith("/api/")) {
			response.addHeader(HttpHeaders.VARY, DocForm.HEADER);
		}
		DocForm.set(DocForm.of(request.getHeader(DocForm.HEADER)));
		try {
			chain.doFilter(request, response);
		}
		finally {
			DocForm.set(null);
		}
	}
}
