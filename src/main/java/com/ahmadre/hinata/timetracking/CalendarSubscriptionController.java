package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.UserWords;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * The caller's own calendar subscriptions (HIN-94).
 *
 * <p>Behind {@link AdvancedTimeTrackingGate} and the organisation's {@code icsImportEnabled}: with
 * either off every route here is 404 {@code error.feature.disabled}. No response carries the
 * address, only its host.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/me/calendar-subscriptions")
@RequiredArgsConstructor
public class CalendarSubscriptionController {

	private final CalendarSubscriptionService subscriptions;
	private final CurrentUser currentUser;
	private final UserWords words;

	public record RuleRequest(boolean enabled, @Size(max = 64) String projectId,
			@Size(max = 20) List<@Size(max = TimeTag.MAX_NAME) String> tags, Boolean billable) {

		CalendarSubscriptionService.RuleDraft toDraft() {
			return new CalendarSubscriptionService.RuleDraft(enabled, projectId, tags, billable);
		}
	}

	public record SubscriptionRequest(@Size(max = CalendarSubscription.NAME_MAX) String name,
			@Size(max = 2048) String url, @Size(max = 7) String color, Boolean enabled, @Valid RuleRequest autoConvert) {
	}

	public record RuleResponse(boolean enabled, String projectId, List<String> tags, Boolean billable,
			Instant since) {
	}

	/** {@code message} is {@code messageKey} in the reader's language. */
	public record SkipResponse(String eventId, Instant startsAt, String messageKey, String message, Instant at) {
	}

	/**
	 * A subscription as its owner sees it. {@code hostMasked} is the host followed by an ellipsis;
	 * {@code lastError} is a message key, with {@code lastErrorArg} its argument, and
	 * {@code lastErrorMessage} the sentence in the reader's language.
	 */
	public record SubscriptionResponse(String id, String name, String color, String hostMasked, boolean enabled,
			RuleResponse autoConvert, CalendarSubscription.Status status, String lastError, Integer lastErrorArg,
			String lastErrorMessage, Instant lastFetchedAt, int failures, int eventCount, List<SkipResponse> skips,
			Instant createdAt) {

		static SubscriptionResponse from(CalendarSubscription subscription) {
			return from(subscription, null);
		}

		static SubscriptionResponse from(CalendarSubscription subscription, UserWords words) {
			Locale locale = LocaleContextHolder.getLocale();
			BiFunction<String, Integer, String> say = (key, arg) -> key == null || words == null
					? null : arg == null ? words.in(locale, key) : words.in(locale, key, arg);
			CalendarSubscription.AutoConvert rule = subscription.getAutoConvert() == null
					? CalendarSubscription.AutoConvert.OFF : subscription.getAutoConvert();
			return new SubscriptionResponse(subscription.getId(), subscription.getName(), subscription.getColor(),
					subscription.getHostMasked() == null ? null : subscription.getHostMasked() + "/…",
					subscription.isEnabled(),
					new RuleResponse(rule.enabled(), rule.projectId(), rule.tags(), rule.billable(), rule.since()),
					subscription.getLastStatus(), subscription.getLastError(), subscription.getLastErrorArg(),
					say.apply(subscription.getLastError(), subscription.getLastErrorArg()),
					subscription.getLastFetchedAt(), subscription.getFailures(), subscription.getEventCount(),
					subscription.getSkips().stream()
							.map(skip -> new SkipResponse(skip.eventId(), skip.startsAt(), skip.messageKey(),
									say.apply(skip.messageKey(), null), skip.at()))
							.toList(),
					subscription.getCreatedAt());
		}
	}

	private SubscriptionResponse respond(CalendarSubscription subscription) {
		return SubscriptionResponse.from(subscription, words);
	}

	/** At most {@value CalendarSubscription#PER_USER_MAX}, so there is no page. */
	@GetMapping
	public List<SubscriptionResponse> list() {
		return subscriptions.list(currentUser.require()).stream().map(this::respond).toList();
	}

	@GetMapping("/{id}")
	public SubscriptionResponse get(@PathVariable String id) {
		return respond(subscriptions.require(currentUser.require(), id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SubscriptionResponse create(@Valid @RequestBody SubscriptionRequest body) {
		return respond(subscriptions.create(currentUser.require(),
				new CalendarSubscriptionService.Draft(body.name(), body.url(), body.color(), body.enabled(),
						body.autoConvert() == null ? null : body.autoConvert().toDraft())));
	}

	@PatchMapping("/{id}")
	public SubscriptionResponse update(@PathVariable String id, @Valid @RequestBody SubscriptionRequest body) {
		return respond(subscriptions.update(currentUser.require(), id,
				new CalendarSubscriptionService.Patch(body.name(), body.url(), body.color(), body.enabled(),
						body.autoConvert() == null ? null : body.autoConvert().toDraft())));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		subscriptions.delete(currentUser.require(), id);
	}

	/** Reads the calendar now, once a minute at most; answers at once, usually in the state {@code RUNNING}. */
	@PostMapping("/{id}/refresh")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public SubscriptionResponse refresh(@PathVariable String id) {
		return respond(subscriptions.refresh(currentUser.require(), id));
	}
}
