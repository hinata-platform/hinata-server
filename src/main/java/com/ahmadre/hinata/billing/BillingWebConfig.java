package com.ahmadre.hinata.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link BillingGate} in front of every billing route. */
@Configuration
@RequiredArgsConstructor
public class BillingWebConfig implements WebMvcConfigurer {

	private final BillingGate gate;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(gate).addPathPatterns(BillingGate.GATED_PATTERNS);
	}
}
