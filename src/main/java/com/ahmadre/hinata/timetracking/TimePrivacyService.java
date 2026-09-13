package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a person is told about the processing of their working time (Art. 12–14
 * DSGVO), and the evidence that they were.
 *
 * <p>Two halves. The <b>notice</b> is prose: the operator's own text when they
 * wrote one, otherwise the built-in template in the reader's language. The
 * <b>visibility</b> is not prose and is never maintained by hand — it is computed,
 * on every read, from the policies that are actually in force. A panel an
 * administrator had to keep in step with the switches would be wrong the first
 * time somebody forgot, and a transparency panel that is wrong is worse than none.
 * Every later stage that makes working time legible to somebody new adds a field
 * here, in the same move as the feature.
 */
@Service
@RequiredArgsConstructor
public class TimePrivacyService {

	/** Where the built-in notice lives, one file per language: {@code notice_de.md}. */
	static final String TEMPLATE_DIR = "time-privacy/";

	private final TimeTrackingSettings policy;
	private final AuditService audit;
	private final UserRepository users;
	private final UserWords words;
	private final MongoTemplate mongo;
	private final Clock clock;

	/** The templates by language, read once — they ship with the build. */
	private final Map<String, String> templates = new ConcurrentHashMap<>();

	/**
	 * Who can see what of a person's time, as the rules stand right now.
	 *
	 * <p>Only the answers that depend on a policy. That the person sees their own
	 * entries and that administrators can see every entry holds on every instance,
	 * and a field that can only ever be {@code true} is a field a client has to
	 * pretend could be something else.
	 *
	 * @param leadsSeeEntries       leads of the person's projects see their entries,
	 *                              their history and the rows of the timesheet
	 * @param approvalsEnabled      periods are handed in and signed off by a lead
	 * @param workloadReports       reports of booked time against capacity, per person
	 * @param alerts                leads are notified when a project passes a threshold
	 * @param targetReminders       the person is reminded about their own target
	 * @param arbzgHints            Working Hours Act hints, to the person only
	 * @param lateEntryHintDays     "recorded late" hint after this many days, or null
	 * @param maxDaysBack           how far back a day can be recorded without an exception
	 * @param entryRetentionMonths  entries are deleted after this many months; 0 never
	 * @param descriptionRetentionMonths descriptions of deleted accounts are emptied
	 *                              after this many months; 0 never
	 * @param foreignChangesRecorded somebody else changing the person's entries is
	 *                              written to the audit log (and to the entry's history)
	 * @param entryCreationRecorded filing an entry is written to the audit log
	 * @param timerEventsRecorded   starting and stopping a timer is written to the audit log
	 */
	public record Visibility(boolean leadsSeeEntries, boolean approvalsEnabled,
			boolean workloadReports, boolean alerts, boolean targetReminders, boolean arbzgHints,
			Integer lateEntryHintDays, int maxDaysBack, int entryRetentionMonths,
			int descriptionRetentionMonths, boolean foreignChangesRecorded,
			boolean entryCreationRecorded, boolean timerEventsRecorded) {
	}

	/**
	 * The notice, whether it is the operator's own, when the person confirmed it,
	 * and the computed visibility.
	 */
	public record Privacy(String notice, boolean customNotice, Instant acknowledgedAt,
			Visibility visibility) {
	}

	public Privacy privacy(User user) {
		String custom = policy.privacyNotice();
		boolean isCustom = custom != null && !custom.isBlank();
		return new Privacy(isCustom ? custom : template(words.localeOf(user).getLanguage()),
				isCustom, user.getTimePrivacyAcknowledgedAt(), visibility());
	}

	/**
	 * Records that the person has seen the notice.
	 *
	 * <p>The first moment is the one kept: the question it answers is "were they
	 * informed before the module processed their time", and a later confirmation
	 * overwriting it would move that answer forward. Conditional in the query, so
	 * two devices confirming at once still leave the earlier one.
	 */
	public Privacy acknowledge(User user) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getId())
						.and("timePrivacyAcknowledgedAt").is(null)),
				new Update().set("timePrivacyAcknowledgedAt", clock.instant()), User.class);
		User current = users.findById(user.getId()).orElse(user);
		return privacy(current);
	}

	Visibility visibility() {
		TimeTrackingSettings.Retention retention = policy.retention();
		return new Visibility(
				policy.leadsSeeMemberEntries(),
				policy.approvalsEnabled(),
				policy.workloadReportsEnabled(),
				policy.alertsEnabled(),
				policy.targetRemindersEnabled(),
				policy.arbzgHintsEnabled(),
				policy.lateEntryHintDays(),
				policy.maxDaysBack(),
				retention.entryPurgeMonths(),
				retention.descriptionPurgeMonths(),
				audit.isEnabled(AuditAction.TIME_ENTRY_UPDATED)
						|| audit.isEnabled(AuditAction.TIME_ENTRY_DELETED),
				audit.isEnabled(AuditAction.TIME_ENTRY_CREATED),
				audit.isEnabled(AuditAction.TIME_TIMER_STARTED)
						|| audit.isEnabled(AuditAction.TIME_TIMER_STOPPED)
						|| audit.isEnabled(AuditAction.TIME_TIMER_DISCARDED));
	}

	/** The built-in notice in {@code language}, or in English when there is none. */
	String template(String language) {
		return templates.computeIfAbsent(language == null ? "" : language, lang -> {
			String localized = read(TEMPLATE_DIR + "notice_" + lang + ".md");
			return localized != null ? localized : read(TEMPLATE_DIR + "notice_en.md");
		});
	}

	private static String read(String path) {
		ClassPathResource resource = new ClassPathResource(path);
		if (!resource.exists()) {
			return null;
		}
		try (InputStream in = resource.getInputStream()) {
			return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
