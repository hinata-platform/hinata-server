package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * The one place billing is switched off for clients.
 *
 * <p>Same shape as {@code timeoff.AbsenceManagementGate}: with the switch off the routes do not
 * exist, so the answer is 404 with {@code error.feature.disabled}, and the app re-reads
 * {@code /meta} instead of showing an error. The extended module's own gate covers these paths
 * as well; this one adds the billing switch on top.
 */
@Component
@RequiredArgsConstructor
public class BillingGate implements HandlerInterceptor {

	/** Everything billing owns, in both forms, because {@code /a/**} need not match {@code /a}. */
	public static final List<String> GATED_PATTERNS = List.of("/api/v1/billing", "/api/v1/billing/**");

	/** What a client sees for every gated path while billing is off. */
	public static final String DISABLED_KEY = "error.feature.disabled";

	private final BillingSettings settings;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!settings.enabled()) {
			throw new ApiException(HttpStatus.NOT_FOUND, DISABLED_KEY);
		}
		return true;
	}
}
