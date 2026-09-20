package com.ahmadre.hinata.template;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Hangs {@link ProjectTemplateGate} on the module's routes.
 *
 * <p>The registration lives in the module, like {@code timeoff.TimeOffWebConfig}, so the core
 * packages carry no knowledge of it — the same direction the feature flag takes, where the module
 * registers itself through {@code FeatureFlags.Module} instead of being named in
 * {@code MetaController}. A module that can be removed by deleting its package is the point.
 */
@Configuration
@RequiredArgsConstructor
public class ProjectTemplateWebConfig implements WebMvcConfigurer {

	private final ProjectTemplateGate gate;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(gate).addPathPatterns(ProjectTemplateGate.GATED_PATTERNS);
	}
}
