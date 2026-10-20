package com.ahmadre.hinata.shifts;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The thread the module's follow-ups run on after a commit (HIN-27): moving people up from a
 * waiting list ({@link ShiftWaitlist#onSeatsOpened}) and then telling planners a shift is short
 * ({@link ShiftDelivery#onWithdrawn}). Neither keeps the request that freed the place waiting.
 *
 * <p>One thread on purpose. Tasks run in the order they were handed over, and the after-commit
 * listeners hand over moving up before the short notice, so a place the waiting list fills is
 * never reported as short. Moving up is a handful of short transactions per person; one thread
 * keeps up with any real instance and never lets two runs on the same shift collide.
 *
 * <p>A full queue runs the task on the thread that handed it over, as before this executor
 * existed; nothing is dropped. The hourly {@link ShiftWaitlistJob} remains the net under moving up.
 *
 * <p>Not a default candidate, so plain {@code @Async} stays on the application's executor; only
 * {@code @Async(EXECUTOR)} reaches this one.
 */
@Configuration
public class ShiftFollowUps {

	public static final String EXECUTOR = "shiftFollowUpExecutor";

	@Bean(name = EXECUTOR, defaultCandidate = false)
	ThreadPoolTaskExecutor shiftFollowUpExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1000);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.setThreadNamePrefix("shift-follow-up-");
		return executor;
	}
}
