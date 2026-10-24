package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.CopiesScrub;
import com.ahmadre.hinata.content.CopySource;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.ScrubReason;
import com.ahmadre.hinata.setup.SettingsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The work that follows a moderation step once it is saved (HIN-169): scrubbing the copies of what
 * a removal took, and from HIN-171 the notice to the person and the message to the reporters.
 *
 * <p>Called right after the step's own write, never as an after-commit listener: the module runs
 * without transactions too, and {@code @TransactionalEventListener} outside a transaction silently
 * never fires. On a single thread of its own, like the shift follow-ups, so the moderator's request
 * does not wait for a scan of the bell. Every step can run twice without harm; the follow-up job
 * (HIN-170) drives what a restart dropped.
 */
@Component
public class ModerationFollowUps {

	public static final String EXECUTOR = "moderationFollowUpExecutor";

	private final CopiesScrub copies;
	private final MessageSource messages;
	private final SettingsService settings;
	private final Executor executor;

	public ModerationFollowUps(CopiesScrub copies, MessageSource messages, SettingsService settings,
			@Qualifier(EXECUTOR) Executor executor) {
		this.copies = copies;
		this.messages = messages;
		this.settings = settings;
		this.executor = executor;
	}

	/** The placeholders in the language of the instance, read once per step. */
	public Placeholders placeholders() {
		return Placeholders.in(messages, settings.instanceLocale());
	}

	/** Scrubs the copies of what [removals] took, off the request. */
	public CompletableFuture<Void> scrubCopies(List<Removal> removals) {
		List<CopySource> sources = removals.stream()
				.map(Removal::copies)
				.filter(Objects::nonNull)
				.toList();
		if (sources.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		Placeholders placeholders = placeholders();
		return CompletableFuture.runAsync(() -> copies.scrub(sources, ScrubReason.REMOVED, placeholders), executor);
	}

	/**
	 * One thread, a queue of a thousand, and the caller's own thread once that is full: nothing is
	 * dropped. Not a default candidate, so nothing else lands on it.
	 */
	@Configuration
	static class Threads {

		@Bean(name = EXECUTOR, defaultCandidate = false)
		ThreadPoolTaskExecutor moderationFollowUpExecutor() {
			ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
			executor.setCorePoolSize(1);
			executor.setMaxPoolSize(1);
			executor.setQueueCapacity(1000);
			executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
			executor.setWaitForTasksToCompleteOnShutdown(true);
			executor.setAwaitTerminationSeconds(30);
			executor.setThreadNamePrefix("moderation-follow-up-");
			return executor;
		}
	}
}
