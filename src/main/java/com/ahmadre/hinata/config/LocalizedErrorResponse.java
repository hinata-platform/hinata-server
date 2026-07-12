package com.ahmadre.hinata.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Instant;

/** Writes the common JSON error shape from servlet filters before MVC is active. */
final class LocalizedErrorResponse {

	private LocalizedErrorResponse() {
	}

	static void write(HttpServletResponse response, HttpStatus status, String message)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write("{\"status\":" + status.value()
				+ ",\"error\":\"" + escape(status.getReasonPhrase())
				+ "\",\"message\":\"" + escape(message)
				+ "\",\"timestamp\":\"" + Instant.now() + "\"}");
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (c < 0x20) {
						escaped.append(String.format("\\u%04x", (int) c));
					}
					else {
						escaped.append(c);
					}
				}
			}
		}
		return escaped.toString();
	}
}
