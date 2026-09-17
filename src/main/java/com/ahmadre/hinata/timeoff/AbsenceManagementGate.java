package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * The one place absence management is switched off for clients.
 *
 * <p>Same shape and same reasoning as {@code timetracking.AdvancedTimeTrackingGate}: with the flag
 * off the routes do not <em>exist</em>, so the answer is 404 rather than 403, and the body carries
 * {@code error.feature.disabled} so the app can tell it apart from a genuine not-found and re-read
 * {@code /meta} instead of showing an error page. {@code /api/v1/meta} publishes every flag by
 * name, so this buys clarity rather than secrecy.
 *
 * <p>It covers HTTP and only HTTP. Whatever reaches the services another way — the MCP tools of
 * stage 16, the yearly run of A4, the demo seeder — asks {@link TimeOffSettings#enabled()} itself,
 * because no interceptor stands in front of those.
 *
 * <p>Like its sibling, the prefixes are gated before their controllers exist. Until A1's handlers
 * land these paths 404 by accident and afterwards on purpose, with the same body either way, so
 * nothing about the client's behaviour changes on the day they arrive.
 */
@Component
@RequiredArgsConstructor
public class AbsenceManagementGate implements HandlerInterceptor {

	/**
	 * Everything the module owns. Both forms are listed because {@code /a/**} is not required to
	 * match {@code /a} itself, and a client calling the collection asks for the bare path.
	 */
	public static final List<String> GATED_PATTERNS = List.of(
			"/api/v1/time-off", "/api/v1/time-off/**");

	/** What a client sees for every gated path while the module is off. */
	public static final String DISABLED_KEY = "error.feature.disabled";

	private final TimeOffSettings settings;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
			Object handler) {
		if (!settings.enabled()) {
			throw new ApiException(HttpStatus.NOT_FOUND, DISABLED_KEY);
		}
		return true;
	}
}
