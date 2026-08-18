package com.ahmadre.hinata.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application's source of "now".
 *
 * <p>Everything scheduled has a timing rule that is only ever exercised by
 * waiting — a five-minute quiet window, a thirty-minute ceiling. Reading the
 * clock through an injected bean means those rules can be tested by moving time
 * instead of by sleeping through it, and a test that sleeps for 45 minutes is a
 * test nobody runs.
 *
 * <p>UTC, like the rest of the JVM (see {@code HinataServerApplication}).
 */
@Configuration
public class ClockConfig {

	@Bean
	@ConditionalOnMissingBean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
