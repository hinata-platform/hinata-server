package com.ahmadre.hinata.timeoff;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Hangs {@link AbsenceManagementGate} on the module's route prefix.
 *
 * <p>The registration lives in the module, like {@code timetracking.TimeTrackingWebConfig}, so the
 * core packages carry no knowledge of it — the same direction the feature flag takes, where the
 * module registers itself through {@code FeatureFlags.Module} instead of being named in
 * {@code MetaController}. A module that can be removed by deleting its package is the point.
 */
@Configuration
@RequiredArgsConstructor
public class TimeOffWebConfig implements WebMvcConfigurer {

	private final AbsenceManagementGate gate;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(gate).addPathPatterns(AbsenceManagementGate.GATED_PATTERNS);
	}
}
