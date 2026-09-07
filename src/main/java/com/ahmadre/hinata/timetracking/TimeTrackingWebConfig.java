package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Hangs {@link AdvancedTimeTrackingGate} on the module's route prefixes.
 *
 * <p>The registration lives in the module rather than in a central MVC config so
 * that the core packages carry no knowledge of it — the same direction the
 * feature flag takes, where the module registers itself with
 * {@code FeatureFlags.Module} instead of being named in {@code MetaController}.
 * A module that can be removed by deleting its package is the point of the
 * ArchUnit rules in {@code ModuleBoundaryTest}.
 */
@Configuration
@RequiredArgsConstructor
public class TimeTrackingWebConfig implements WebMvcConfigurer {

	private final AdvancedTimeTrackingGate gate;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(gate)
				.addPathPatterns(AdvancedTimeTrackingGate.GATED_PATTERNS);
	}
}
