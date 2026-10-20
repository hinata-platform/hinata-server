package com.ahmadre.hinata.shifts;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import static org.awaitility.Awaitility.await;

/**
 * Waits until the module's follow-up thread ({@link ShiftFollowUps}) has run everything handed to
 * it: moving people up after a freed place and the planners' short notice. A test that frees a
 * place and then looks at the waiting list or the notices calls this first.
 */
public final class ShiftFollowUpsSettled {

	private ShiftFollowUpsSettled() {
	}

	public static void settle(ThreadPoolTaskExecutor followUps) {
		ThreadPoolExecutor pool = followUps.getThreadPoolExecutor();
		await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(10))
				.until(() -> pool.getCompletedTaskCount() == pool.getTaskCount());
	}
}
