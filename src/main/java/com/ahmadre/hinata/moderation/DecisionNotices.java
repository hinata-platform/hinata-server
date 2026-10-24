package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.notification.PersonalNotice;
import com.ahmadre.hinata.setup.OperatorContact;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who hears of a decision (HIN-171), and in which words: the statement of reasons to the person
 * responsible (DSA Art. 17) on its own path, a neutral mail to an address content came in from,
 * and the outcome to everybody who reported (Art. 16(5)). The reporters never learn anything
 * about the other person, the person never learns who reported.
 */
@Component
@RequiredArgsConstructor
class DecisionNotices {

	/** Reports told per round, and rounds per run; a case with more is finished by the next run. */
	static final int BATCH = 100;
	static final int ROUNDS = 50;

	static final String TEMPLATE = "email/moderation-notice";

	private final NotificationService notifications;
	private final MailService mail;
	private final UserRepository users;
	private final UserWords words;
	private final Statements statements;
	private final OperatorContact contact;
	private final SettingsService settings;
	private final HinataProperties props;
	private final MongoTemplate mongo;
	private final Clock clock;

	static String noticeLink(ModerationCase c) {
		return "/notices/" + c.getId();
	}

	/** Whether [decision] owes the person responsible a statement: every action but bulk deceptive spam. */
	static boolean owesStatement(Decision decision) {
		return decision.outcome() == Decision.Outcome.ACTION && !Boolean.TRUE.equals(decision.deceptiveCommercial());
	}

	/**
	 * The statement of [c]: to the person responsible through the bell and a mail they get whatever
	 * their account says; with nobody responsible, a neutral mail to the address the content came
	 * in from, if any.
	 */
	void statement(ModerationCase c) {
		String responsibleId = c.getDecision().responsibleId();
		if (responsibleId != null) {
			users.findById(responsibleId).ifPresent(person ->
					deliver(person, c, Notification.Type.MODERATION_NOTICE, noticeKey(c)));
			return;
		}
		String sender = c.getEvidence() == null ? null : c.getEvidence().externalSender();
		if (sender != null && !sender.isBlank()) {
			neutral(c, sender);
		}
	}

	/** The words of the notice: content removed, account suspended, or both (HIN-172). */
	private static String noticeKey(ModerationCase c) {
		boolean suspended = c.getSuspension() != null;
		if (!suspended) {
			return "moderation.notice.statement";
		}
		return c.getDecision().removal() == null ? "moderation.notice.suspended" : "moderation.notice.removedAndSuspended";
	}

	/** The person responsible told that their notice changed: an appeal decided, the content back. */
	void changed(ModerationCase c) {
		String responsibleId = c.getDecision().responsibleId();
		if (responsibleId != null) {
			users.findById(responsibleId).ifPresent(person ->
					deliver(person, c, Notification.Type.MODERATION_NOTICE_UPDATED, "moderation.notice.changed"));
		}
	}

	/** Whether [person] can sign in to appeal in the app; otherwise they appeal by replying. */
	static boolean canSignIn(User person) {
		return person.isActive();
	}

	private void deliver(User person, ModerationCase c, Notification.Type type, String key) {
		Locale locale = words.localeOf(person);
		ZoneId zone = UserZones.of(person, settings.get());
		boolean signsIn = canSignIn(person);
		Statement statement = statements.of(c, c.getDecision(), locale, zone, signsIn, clock.instant());
		PersonalNotice notice = new PersonalNotice(words.in(locale, key + ".title"), words.in(locale, key + ".body"),
				words.in(locale, "moderation.notice.statementPush"), words.in(locale, "moderation.notice.statementPushBody"),
				null);
		Map<String, Object> model = model(statement, locale, "email.eyebrow." + type.name());
		model.put("headline", notice.title());
		model.put("ctaLabel", words.in(locale, "moderation.notice.open"));
		notifications.deliverNotice(person, type, noticeLink(c), notice,
				new NotificationService.NoticeMail(TEMPLATE, model, signsIn ? null : contact.current().contactEmail()));
	}

	/**
	 * The neutral mail to an address with no account behind it (content from e-mail): no project,
	 * no key, no words of the content and none of moderation's findings, only what was done, on
	 * which ground, and that a reply to the contact is an appeal.
	 */
	private void neutral(ModerationCase c, String address) {
		Locale locale = settings.instanceLocale();
		ZoneId zone = UserZones.of(null, settings.get());
		Statement statement = statements.of(c, c.getDecision(), locale, zone, false, clock.instant());
		Instant written = c.getEvidence().createdAt();
		Map<String, Object> model = model(statement, locale, "email.eyebrow.MODERATION_NOTICE");
		String headline = words.in(locale, "moderation.neutral.headline");
		model.put("headline", headline);
		model.put("measure", words.in(locale, "moderation.neutral.measure",
				written == null ? "" : Statements.date(written, locale, zone), mail.organizationName()));
		model.remove("facts");
		model.remove("message");
		mail.sendTemplate(address, mail.subjectPrefix() + headline, TEMPLATE, model, contact.current().contactEmail());
	}

	private Map<String, Object> model(Statement statement, Locale locale, String eyebrowKey) {
		Map<String, Object> model = new HashMap<>();
		model.put("locale", locale.getLanguage());
		model.put("eyebrowKey", eyebrowKey);
		model.put("measure", statement.measure());
		model.put("basis", statement.basis());
		model.put("facts", statement.facts());
		model.put("ground", statement.groundText());
		model.put("automation", statement.automation());
		model.put("appeal", statement.appeal());
		model.put("courts", statement.courtsNote());
		model.put("message", statement.messageToAuthor());
		model.put("rules", props.getBaseUrl() == null ? null
				: words.in(locale, "moderation.statement.label.rules") + ": " + props.getBaseUrl() + statement.rulesUrl());
		for (String label : List.of("basis", "facts", "ground", "automation", "message")) {
			model.put(label + "Label", words.in(locale, "moderation.statement.label." + label));
		}
		return model;
	}

	/**
	 * Tells everybody who reported [c] what came of it, a hundred at a time, each report marked as
	 * told in the same write that marks it removed. Answers whether nobody is left to tell.
	 */
	boolean reporters(ModerationCase c) {
		Decision decision = c.getDecision();
		boolean removed = decision.outcome() == Decision.Outcome.ACTION && decision.removal() != null
				&& c.getRestoration() == null;
		String key = "moderation.decision." + decision.outcome();
		for (int round = 0; round < ROUNDS; round++) {
			Query untold = Query.query(Criteria.where("caseId").is(c.getId()).and("decisionNoticeSentAt").is(null))
					.limit(BATCH);
			List<ContentReport> batch = mongo.find(untold, ContentReport.class);
			if (batch.isEmpty()) {
				return true;
			}
			List<String> members = new ArrayList<>();
			for (ContentReport report : batch) {
				if (report.getReporterId() != null) {
					members.add(report.getReporterId());
				}
				else if (report.getNotifier() != null && report.getNotifier().email() != null) {
					// Without an account: no ids, and nothing of what they wrote (HIN-175).
					Locale locale = settings.instanceLocale();
					notifications.mailWorded(report.getNotifier().email(), locale,
							words.in(locale, "moderation.decision.title"), outcomeText(locale, key));
				}
			}
			if (!members.isEmpty()) {
				List<User> people = new ArrayList<>();
				users.findAllById(members).forEach(people::add);
				notifications.notifyPeople(Notification.Type.MODERATION_DECISION, people, null,
						person -> outcomeNotice(person, key), null);
			}
			mongo.updateMulti(Query.query(Criteria.where("_id").in(batch.stream().map(ContentReport::getId).toList())),
					new Update().set("decisionNoticeSentAt", clock.instant()).set("removed", removed),
					ContentReport.class);
		}
		return false;
	}

	private PersonalNotice outcomeNotice(User person, String key) {
		Locale locale = words.localeOf(person);
		return new PersonalNotice(words.in(locale, "moderation.decision.title"), outcomeText(locale, key),
				words.in(locale, "moderation.notice.statementPush"), words.in(locale, "moderation.notice.statementPushBody"),
				null);
	}

	/** The outcome, and that the courts are open regardless (DSA Art. 16(5)). */
	private String outcomeText(Locale locale, String key) {
		return words.in(locale, key) + " " + words.in(locale, "moderation.statement.courts");
	}
}
