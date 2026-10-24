package com.ahmadre.hinata.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Registers {@link TrustedForwardedHeaderFilter} where {@code server.forward-headers-strategy:
 * framework} would put Spring's own filter: first, around every request, error dispatches included.
 * The strategy is {@code none} for that reason.
 */
@Configuration
class ForwardedHeadersConfig {

	@Bean
	TrustedForwardedHeaderFilter trustedForwardedHeaderFilter(HinataProperties properties) {
		return new TrustedForwardedHeaderFilter(properties.getSecurity().getTrustedProxies());
	}

	/**
	 * Typed as a registration of Spring's filter, so that whatever registers one only when none is
	 * there, springdoc's Scalar page among them, finds this one and stays out.
	 */
	@Bean
	FilterRegistrationBean<ForwardedHeaderFilter> trustedForwardedHeaderFilterRegistration(
			TrustedForwardedHeaderFilter filter) {
		FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		registration.setName("trustedForwardedHeaderFilter");
		return registration;
	}
}
