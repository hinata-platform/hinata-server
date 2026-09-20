package com.ahmadre.hinata.template;

import com.ahmadre.hinata.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * The one place project templates are switched off for clients.
 *
 * <p>Same shape and same reasoning as {@code timeoff.AbsenceManagementGate}: with the flag off the
 * routes do not <em>exist</em>, so the answer is 404 rather than 403, and the body carries
 * {@code error.feature.disabled} so the app can tell it apart from a genuine not-found and re-read
 * {@code /meta} instead of showing an error page. {@code /api/v1/meta} publishes every flag by
 * name, so this buys clarity rather than secrecy.
 *
 * <p><b>Only what would not exist without the feature is listed.</b> {@code PATCH
 * /api/v1/projects/{id}} and every issue endpoint stay open whatever the switch says. A project
 * that could not be renamed because templates are off would be a broken product, not a disabled
 * feature; the two fields those endpoints gained are refused inside the service instead, where the
 * rest of the request still goes through.
 *
 * <p>It covers HTTP and only HTTP. Whatever reaches the services another way — the MCP tools of
 * stage 8, the demo seeder — asks {@link ProjectTemplateSettings#enabled()} itself, because no
 * interceptor stands in front of those.
 */
@Component
@RequiredArgsConstructor
public class ProjectTemplateGate implements HandlerInterceptor {

	/**
	 * Everything the module owns, as path patterns. {@code *} is one segment, which is the project
	 * id; {@code /schedule} is listed in both forms because {@code /a/**} is not required to match
	 * {@code /a} itself, and a client calling the collection asks for the bare path.
	 */
	public static final List<String> GATED_PATTERNS = List.of(
			"/api/v1/projects/*/copy",
			"/api/v1/projects/*/instantiate",
			"/api/v1/projects/*/schedule",
			"/api/v1/projects/*/schedule/**");

	/** What a client sees for every gated path while the module is off. */
	public static final String DISABLED_KEY = "error.feature.disabled";

	private final ProjectTemplateSettings settings;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
			Object handler) {
		if (!settings.enabled()) {
			throw new ApiException(HttpStatus.NOT_FOUND, DISABLED_KEY);
		}
		return true;
	}
}
