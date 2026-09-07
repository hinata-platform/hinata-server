package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * The one place the extended time-tracking module is switched off for clients.
 *
 * <p>With the flag off the extended routes do not <em>exist</em>: the answer is
 * 404, not 403. A 403 would be an admission that the caller is merely not
 * allowed near a feature that is here, which is a worse experience — the app
 * would show "denied" where the honest answer is "not on this server". The body
 * is the ordinary error shape with its own code, {@code error.feature.disabled},
 * so the app can tell it apart from a genuine 404 and re-read {@code /meta}
 * instead of showing a not-found page.
 *
 * <p>This is not concealment, and should not be described as such:
 * {@code /api/v1/meta} is public and publishes every feature flag by name, so
 * anyone can read the module inventory of any instance without signing in. That
 * is deliberate — the app needs the flags before there is a session — and it
 * means the 404 buys clarity, not secrecy. The gate still runs behind
 * authentication, so an anonymous caller gets 401 here and learns nothing extra.
 *
 * <p>It covers HTTP and only HTTP. The MCP tools, the smart-commit handler and
 * the demo seeder reach {@code TimeTrackingService} directly and never pass a
 * {@code DispatcherServlet} interceptor — {@code /mcp} has its own filter chain.
 * Today that is harmless, because those callers only touch the ungated 1.x
 * surface; the first extended MCP tool has to check
 * {@link TimeTrackingSettings#advancedEnabled()} in the service it calls.
 *
 * <p>One interceptor over the prefixes rather than a check inside each
 * controller, and no {@code @ConditionalOnProperty} on the controllers either:
 * an administrator turning the module on has to take effect on the next request,
 * not on the next restart. {@link TimeTrackingSettings} caches the answer, so
 * this costs a volatile read.
 *
 * <p>The prefixes below are gated before their controllers exist. That is the
 * point: until stage 3 puts handlers behind them these paths 404 by accident,
 * and afterwards they 404 on purpose — with the same body either way, so the
 * client's behaviour does not change on the day the handlers land.
 */
@Component
@RequiredArgsConstructor
public class AdvancedTimeTrackingGate implements HandlerInterceptor {

	/**
	 * Everything the extended module owns. Both forms of each prefix are listed:
	 * {@code /a/**} is not required to match {@code /a} itself, and the bare path
	 * is exactly what a client calls for a single resource like the timer.
	 */
	public static final List<String> GATED_PATTERNS = List.of(
			"/api/v1/time", "/api/v1/time/**",
			"/api/v1/me/timer", "/api/v1/me/timer/**",
			"/api/v1/availability", "/api/v1/availability/**",
			"/api/v1/billing", "/api/v1/billing/**",
			"/api/v1/me/calendar-subscriptions", "/api/v1/me/calendar-subscriptions/**");

	/** What a client sees for every gated path while the module is off. */
	public static final String DISABLED_KEY = "error.feature.disabled";

	private final TimeTrackingSettings settings;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
			Object handler) {
		if (!settings.advancedEnabled()) {
			throw new ApiException(HttpStatus.NOT_FOUND, DISABLED_KEY);
		}
		return true;
	}
}
