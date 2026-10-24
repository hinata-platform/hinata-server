package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reports from people without an account (HIN-175, DSA Art. 16): what the public form files once
 * its token and limits passed. The address somebody gives is read, never fetched; when it is one of
 * this instance's, the report lands on exactly that content, and on a case of kind {@code OTHER}
 * with the address as its location otherwise. The form says the same either way, so nobody learns
 * from it whether something exists.
 */
@Service
@RequiredArgsConstructor
class PublicReports {

	/** Reports one address may send in a day; spelled without a {@code +tag} and in lower case. */
	static final int PER_EMAIL_PER_DAY = 3;
	static final int LOCATION_MAX = 300;
	static final int NAME_MAX = 100;
	static final int EMAIL_MAX = 254;

	/** The one problem whose line names a number: how long an explanation has to be at least. */
	static final String EXPLANATION_PROBLEM = "web.moderation.report.error.explanation";

	/**
	 * One mailbox, nothing else: no name, no list, no comment. A name in angle brackets or a second
	 * address would let one mailbox count as many for the limit, while mail still reaches it.
	 */
	private static final Pattern EMAIL = Pattern.compile(
			"[^@\\s<>()\\[\\],;:\"\\\\]+@[^@\\s<>()\\[\\],;:\"\\\\]+\\.[^@\\s<>()\\[\\],;:\"\\\\]+");

	private final ContentReportService reports;
	private final List<ContentSurface> surfaces;
	private final ModerationNotices notices;
	private final NotificationService notifications;
	private final MongoTemplate mongo;
	private final HinataProperties properties;
	private final Clock clock;

	/** What somebody sent. Blank strings count as nothing. */
	record Submission(String location, ReportCategory category, String explanation, String name, String email,
			boolean goodFaith) {
	}

	/** What is wrong with a submission, as the key of the line the form shows; empty when nothing is. */
	Optional<String> problemWith(Submission submission) {
		if (blank(submission.location())) {
			return Optional.of("web.moderation.report.error.location");
		}
		if (submission.category() == null) {
			return Optional.of("web.moderation.report.error.category");
		}
		if (ContentReportService.EXPLAINED.contains(submission.category()) && (submission.explanation() == null
				|| submission.explanation().strip().length() < ContentReportService.EXPLANATION_MIN)) {
			return Optional.of(EXPLANATION_PROBLEM);
		}
		if (!blank(submission.email()) && (submission.email().strip().length() > EMAIL_MAX
				|| !EMAIL.matcher(submission.email().strip()).matches())) {
			return Optional.of("web.moderation.report.error.email");
		}
		if (!submission.goodFaith()) {
			return Optional.of("web.moderation.report.error.goodFaith");
		}
		return Optional.empty();
	}

	/** Files [submission], sends the receipt in [locale] when there is an address. */
	void file(Submission submission, Locale locale) {
		String email = blank(submission.email()) ? null : submission.email().strip();
		Notifier notifier = email == null ? null
				: new Notifier(clip(submission.name(), NAME_MAX), email);
		ContentReportService.Source source = new ContentReportService.Source(ReportSource.PUBLIC,
				"public:" + UUID.randomUUID(), null, notifier);
		ContentReportService.Filed filed = reports.file(targetOf(submission.location().strip()),
				submission.category(), clip(submission.explanation(), ContentReport.EXPLANATION_MAX_PUBLIC), source);
		notices.reportFiled(filed, false);
		if (email != null) {
			// The receipt never repeats what was entered: the form would be a way to send mail otherwise.
			notifications.mailAddress(email, locale, "moderation.receipt.title", "moderation.receipt.body");
		}
	}

	/**
	 * The content [location] names when it is an address of this instance and the content is there,
	 * else a place of kind {@code OTHER}. Read, never fetched.
	 */
	ContentReportService.Target targetOf(String location) {
		String place = clip(location, LOCATION_MAX);
		Optional<ContentReportService.Target> found = refOf(place).flatMap(ref -> surfaces.stream()
				.filter(surface -> surface.kind() == ref.kind()).findFirst()
				.flatMap(surface -> surface.forModeration(ref)))
				.filter(snapshot -> !snapshot.removed())
				// Where it is, in the reporter's words: the content's own title is part of what the
				// case withholds until a second person opens it.
				.map(snapshot -> new ContentReportService.Target(snapshot.ref(), snapshot, place));
		return found.orElseGet(() -> new ContentReportService.Target(new ContentRef(ContentKind.OTHER, null, null, null),
				null, place));
	}

	/** The content an address of this instance points at: {@code /issues/X}, {@code /knowledge/X}, {@code /people/X}. */
	Optional<TargetRef> refOf(String location) {
		UriComponents uri;
		try {
			uri = UriComponentsBuilder.fromUriString(location).build();
		}
		catch (IllegalArgumentException notAnAddress) {
			return Optional.empty();
		}
		if (uri.getHost() == null || !own(uri.getHost())) {
			return Optional.empty();
		}
		List<String> path = uri.getPathSegments();
		if (path.size() != 2) {
			return Optional.empty();
		}
		String id = path.get(1);
		return switch (path.get(0)) {
			case "issues", "browse" -> {
				String comment = uri.getQueryParams().getFirst("comment");
				String attachment = uri.getQueryParams().getFirst("attachment");
				if (comment != null) {
					yield Optional.of(new TargetRef(ContentKind.COMMENT, comment, null));
				}
				yield Optional.of(attachment != null ? new TargetRef(ContentKind.ATTACHMENT, attachment, id)
						: TargetRef.of(ContentKind.ISSUE, id));
			}
			case "knowledge" -> Optional.of(TargetRef.of(ContentKind.ARTICLE, id));
			case "people" -> Optional.of(TargetRef.of(ContentKind.PERSON, id));
			default -> Optional.empty();
		};
	}

	private boolean own(String host) {
		return host.equalsIgnoreCase(hostOf(properties.getBaseUrl())) || host.equalsIgnoreCase(hostOf(properties.webBase()));
	}

	private static String hostOf(String url) {
		try {
			String host = URI.create(url.strip()).getHost();
			return host == null ? "" : host;
		}
		catch (IllegalArgumentException malformed) {
			return "";
		}
	}

	/** [email] as the per-address limit counts it: lower case, without a {@code +tag}. */
	static String countedEmail(String email) {
		if (blank(email)) {
			return null;
		}
		String address = email.strip().toLowerCase(Locale.ROOT);
		int at = address.lastIndexOf('@');
		if (at <= 0) {
			return address;
		}
		String local = address.substring(0, at);
		int plus = local.indexOf('+');
		return (plus > 0 ? local.substring(0, plus) : local) + address.substring(at);
	}

	private static String clip(String text, int max) {
		if (blank(text)) {
			return null;
		}
		return ContentSnapshot.clip(text.strip(), max);
	}

	private static boolean blank(String text) {
		return text == null || text.isBlank();
	}
}
