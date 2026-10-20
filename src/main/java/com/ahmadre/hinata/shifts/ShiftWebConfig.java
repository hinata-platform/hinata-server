package com.ahmadre.hinata.shifts;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Hangs {@link ShiftPlanningGate} on the module's routes.
 *
 * <p>The registration lives in the module, like {@code template.ProjectTemplateWebConfig}, so the
 * core packages carry no knowledge of it. A module that can be removed by deleting its package is
 * the point.
 */
@Configuration
@RequiredArgsConstructor
public class ShiftWebConfig implements WebMvcConfigurer {

	private final ShiftPlanningGate gate;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(gate).addPathPatterns(ShiftPlanningGate.GATED_PATTERNS);
	}
}
