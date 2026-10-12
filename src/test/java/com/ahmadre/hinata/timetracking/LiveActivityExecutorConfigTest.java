package com.ahmadre.hinata.timetracking;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class LiveActivityExecutorConfigTest {

	/**
	 * A full queue refuses loudly instead of dropping quietly or running on the
	 * caller: the push keeps the change and hands it over again.
	 */
	@Test
	void aFullQueueRefusesTheTask() {
		ThreadPoolTaskExecutor executor = new LiveActivityExecutorConfig().liveActivityExecutor();
		executor.initialize();
		try {
			assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
					.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
		}
		finally {
			executor.shutdown();
		}
	}
}
