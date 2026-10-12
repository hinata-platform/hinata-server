package com.ahmadre.hinata.timetracking;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The pool the Live Activity pushes run on (HIN-103), apart from the one every
 * notification push shares: a slow gateway holds two threads here and nothing
 * else, and a burst of timer changes cannot queue ahead of somebody's mention.
 *
 * <p>Bounded on purpose. A full queue refuses the task; {@link TimerLiveActivityPush}
 * then keeps the change and hands it over again later, so nothing is lost and no
 * request thread ever sends.
 *
 * <p>Not a default candidate, so the application's own task executor stays the
 * one every plain {@code @Async} runs on; only {@code @Async("liveActivityExecutor")}
 * and an injection qualified by that name reach this one.
 */
@Configuration
public class LiveActivityExecutorConfig {

	public static final String EXECUTOR = "liveActivityExecutor";

	@Bean(name = EXECUTOR, defaultCandidate = false)
	ThreadPoolTaskExecutor liveActivityExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(200);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setThreadNamePrefix("live-activity-");
		return executor;
	}
}
