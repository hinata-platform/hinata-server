package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * The one place shift planning is switched off for clients.
 *
 * <p>Same shape and reasoning as {@code timetracking.AdvancedTimeTrackingGate}: with the module off
 * its routes do not <em>exist</em>, so the answer is 404 rather than 403, with the body
 * {@code error.feature.disabled} so the app can tell it apart from a genuine not-found and re-read
 * {@code /meta}. {@code /api/v1/meta} publishes every flag by name, so this buys clarity, not
 * secrecy. The gate runs behind authentication; an anonymous caller gets 401 first, except on the feed.
 *
 * <p>The one exception is the calendar feed of HIN-47, which a calendar app fetches without a
 * login: it gets a bare 404 without a body.
 *
 * <p>HTTP only. Jobs and MCP tools pass no interceptor and ask
 * {@link ShiftSettings#requireEnabled()} themselves.
 *
 * <p>The prefixes are gated before their handlers exist (HIN-43 adds them), so a client sees the
 * same body on the day the routes land as on the day before.
 */
@Component
@RequiredArgsConstructor
public class ShiftPlanningGate implements HandlerInterceptor {

	/**
	 * Everything the module owns. Both forms of each prefix are listed: {@code /a/**} is not
	 * required to match {@code /a} itself, and the bare path is what a client calls for a
	 * collection. {@code *} is one segment, the project id.
	 */
	public static final List<String> GATED_PATTERNS = List.of(
			"/api/v1/shifts", "/api/v1/shifts/**",
			"/api/v1/me/shifts", "/api/v1/me/shifts/**",
			"/api/v1/projects/*/shifts", "/api/v1/projects/*/shifts/**");

	private final ShiftSettings settings;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
			Object handler) {
		if (!settings.enabled()) {
			if (request.getRequestURI().startsWith(ShiftFeed.PATH)) {
				// A calendar app asks for text/calendar and could not take the JSON body; and the
				// feed answers every other failure with a bare 404 too, so the address says nothing
				// about why it does not work today (HIN-47).
				response.setStatus(HttpStatus.NOT_FOUND.value());
				return false;
			}
			throw new ApiException(HttpStatus.NOT_FOUND, ShiftSettings.DISABLED_KEY);
		}
		return true;
	}
}
