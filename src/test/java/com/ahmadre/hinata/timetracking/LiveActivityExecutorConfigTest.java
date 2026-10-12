package com.ahmadre.hinata.timetracking;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class LiveActivityExecutorConfigTest {

	/**
	 * A full queue must not drop work: a dropped send of the end would leave a
	 * stopped timer on the lock screen, and a dropped planned send would hold
	 * back every later update of that person.
	 */
	@Test
	void aFullQueueNeverDropsASend() {
		ThreadPoolTaskExecutor executor = new LiveActivityExecutorConfig().liveActivityExecutor();
		executor.initialize();
		try {
			assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
					.isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
		}
		finally {
			executor.shutdown();
		}
	}
}
