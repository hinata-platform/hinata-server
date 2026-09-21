package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Saved reports (HIN-93): a person keeps a report under a name, shares it by link, mails it on a
 * schedule. Only the owner changes one; anybody signed in who holds a live link can open it, and
 * opens it in their own scope ({@link TimeReportScope}) — a link is a way to hand somebody a
 * question, not an answer.
 */
@Service
@RequiredArgsConstructor
public class TimeSavedReportService {

	/** Bytes of randomness in a share token: 256 bits, written as 43 characters of Base64-URL. */
	static final int TOKEN_BYTES = 32;

	private final MongoTemplate mongo;
	private final TimeReportService reports;
	private final SettingsService settings;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	/**
	 * A saved report as its reader sees it: the window already resolved for today on the reader's
	 * clock, and — for anybody but the owner — neither the schedule nor its recipients, which are
	 * other people's business.
	 */
	public record View(String id, String name, TimeSavedReport.Config config, LocalDate from, LocalDate to,
			boolean owned, boolean shared, Instant sharedAt, TimeSavedReport.Schedule schedule, Instant updatedAt) {
	}

	/** A report's name and question, as created or changed. */
	public record Draft(String name, TimeSavedReport.Config config) {
	}

	/** The token of a new link. Shown once: only its hash is kept. */
	public record Share(String token) {
	}

	public record ScheduleDraft(TimeSavedReport.Cadence cadence, DayOfWeek dayOfWeek, Integer hour,
			List<String> recipients) {
	}

	public Page<View> mine(User owner, int page, int size) {
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, TimeReportService.PAGE_MAX),
				Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("_id")));
		Query query = Query.query(Criteria.where("ownerId").is(owner.getId()));
		List<TimeSavedReport> found = mongo.find(Query.of(query).with(pageable), TimeSavedReport.class);
		return PageableExecutionUtils.getPage(found.stream().map(report -> view(report, owner)).toList(), pageable,
				() -> mongo.count(query, TimeSavedReport.class));
	}

	public View create(User owner, Draft draft) {
		if (mongo.count(Query.query(Criteria.where("ownerId").is(owner.getId())), TimeSavedReport.class)
				>= TimeSavedReport.PER_OWNER_MAX) {
			throw ApiException.badRequest("error.time.report.savedLimit", TimeSavedReport.PER_OWNER_MAX);
		}
		Instant now = clock.instant();
		TimeSavedReport saved = mongo.insert(TimeSavedReport.builder()
				.ownerId(owner.getId())
				.name(name(draft.name()))
				.config(checked(draft.config(), owner))
				.createdAt(now)
				.updatedAt(now)
				.build());
		return view(saved, owner);
	}

	public View update(User owner, String id, Draft draft) {
		TimeSavedReport report = own(owner, id);
		if (draft.name() != null) {
			report.setName(name(draft.name()));
		}
		if (draft.config() != null) {
			report.setConfig(checked(draft.config(), owner));
		}
		report.setUpdatedAt(clock.instant());
		return view(mongo.save(report), owner);
	}

	public void delete(User owner, String id) {
		mongo.remove(own(owner, id));
	}

	/** A new link, replacing any earlier one: the old link stops working the moment this answers. */
	public Share share(User owner, String id) {
		TimeSavedReport report = own(owner, id);
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		report.setShareTokenHash(hash(token));
		report.setSharedAt(clock.instant());
		report.setUpdatedAt(clock.instant());
		mongo.save(report);
		return new Share(token);
	}

	public void unshare(User owner, String id) {
		TimeSavedReport report = own(owner, id);
		report.setShareTokenHash(null);
		report.setSharedAt(null);
		report.setUpdatedAt(clock.instant());
		mongo.save(report);
	}

	/**
	 * The report a link points to, for whoever is signed in and opens it. A link that was taken
	 * back or never existed is not found — the same answer, so a guess learns nothing.
	 */
	public View opened(User reader, String token) {
		if (token == null || token.isBlank() || token.length() > 100) {
			throw ApiException.notFound("savedReport");
		}
		TimeSavedReport report = mongo.findOne(Query.query(Criteria.where("shareTokenHash").is(hash(token.strip()))),
				TimeSavedReport.class);
		if (report == null) {
			throw ApiException.notFound("savedReport");
		}
		return view(report, reader);
	}

	/**
	 * A report by id for its owner or for somebody its schedule mails — the link in the mail opens
	 * it. Anybody else is told it is not there, as for a link that never existed.
	 */
	public View openedById(User reader, String id) {
		TimeSavedReport report = mongo.findById(id, TimeSavedReport.class);
		boolean recipient = report != null && report.getSchedule() != null
				&& report.getSchedule().getRecipients().contains(reader.getId());
		if (report == null || !report.getOwnerId().equals(reader.getId()) && !recipient) {
			throw ApiException.notFound("savedReport");
		}
		return view(report, reader);
	}

	/**
	 * Mails the report weekly or monthly to up to {@link TimeSavedReport#RECIPIENTS_MAX} people, each
	 * of whom reads it in their own scope. Only active accounts; the owner receives it only when
	 * they put themselves on the list.
	 */
	public View schedule(User owner, String id, ScheduleDraft draft) {
		TimeSavedReport report = own(owner, id);
		if (draft == null || draft.cadence() == null
				|| draft.cadence() == TimeSavedReport.Cadence.WEEKLY && draft.dayOfWeek() == null) {
			throw ApiException.badRequest("error.time.report.scheduleInvalid");
		}
		int hour = draft.hour() == null ? 7 : draft.hour();
		if (hour < 0 || hour > 23) {
			throw ApiException.badRequest("error.time.report.scheduleInvalid");
		}
		List<String> recipients = recipients(draft.recipients());
		report.setSchedule(TimeSavedReport.Schedule.builder()
				.cadence(draft.cadence())
				.dayOfWeek(draft.cadence() == TimeSavedReport.Cadence.WEEKLY ? draft.dayOfWeek() : null)
				.hour(hour)
				.zone(zoneOf(owner).getId())
				.since(clock.instant())
				.recipients(recipients)
				.build());
		report.setUpdatedAt(clock.instant());
		return view(mongo.save(report), owner);
	}

	public View unschedule(User owner, String id) {
		TimeSavedReport report = own(owner, id);
		report.setSchedule(null);
		report.setUpdatedAt(clock.instant());
		return view(mongo.save(report), owner);
	}

	/**
	 * [config] as the query of a report over [from] to [to]: what the mail runs for each recipient
	 * and what the screen runs for each reader.
	 */
	static TimeReportQuery query(TimeSavedReport.Config config, LocalDate from, LocalDate to) {
		return new TimeReportQuery(from, to, config.getProjectIds(), config.getUserIds(), config.getTeamIds(),
				config.getTags(), config.getBillable(), config.getActivities(), config.getQ(), config.getApproval(),
				config.getRounding(), config.getRoundingIncrement(), null);
	}

	/** The window of a relative range around [today]; the stored days of a custom one. */
	LocalDate[] window(TimeSavedReport.Config config, LocalDate today) {
		DayOfWeek first = reports.weekStart();
		LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(first));
		return switch (config.getRange() == null ? TimeSavedReport.Range.THIS_MONTH : config.getRange()) {
			case THIS_WEEK -> new LocalDate[] { weekStart, weekStart.plusDays(6) };
			case LAST_WEEK -> new LocalDate[] { weekStart.minusDays(7), weekStart.minusDays(1) };
			case THIS_MONTH -> new LocalDate[] { today.withDayOfMonth(1),
					today.with(TemporalAdjusters.lastDayOfMonth()) };
			case LAST_MONTH -> new LocalDate[] { today.minusMonths(1).withDayOfMonth(1),
					today.withDayOfMonth(1).minusDays(1) };
			case LAST_30_DAYS -> new LocalDate[] { today.minusDays(29), today };
			case THIS_YEAR -> new LocalDate[] { today.withDayOfYear(1), today.with(TemporalAdjusters.lastDayOfYear()) };
			case CUSTOM -> new LocalDate[] { config.getFrom(), config.getTo() };
		};
	}

	// --- helpers ------------------------------------------------------------

	private View view(TimeSavedReport report, User reader) {
		boolean owned = report.getOwnerId().equals(reader.getId());
		LocalDate[] window = window(report.getConfig(), LocalDate.ofInstant(clock.instant(), zoneOf(reader)));
		return new View(report.getId(), report.getName(), report.getConfig(), window[0], window[1], owned,
				owned && report.getShareTokenHash() != null, owned ? report.getSharedAt() : null,
				owned ? report.getSchedule() : null, report.getUpdatedAt());
	}

	/**
	 * A config that can run: its window and every filter checked the way a report request is,
	 * so a saved report that is opened a year later meets the same limits.
	 */
	private TimeSavedReport.Config checked(TimeSavedReport.Config config, User owner) {
		if (config == null) {
			throw ApiException.badRequest("error.time.report.configInvalid");
		}
		if (config.getRange() == TimeSavedReport.Range.CUSTOM && (config.getFrom() == null || config.getTo() == null)) {
			throw ApiException.badRequest("error.time.report.invalidRange");
		}
		LocalDate[] window = window(config, LocalDate.ofInstant(clock.instant(), zoneOf(owner)));
		TimeReportFilter filter = reports.filter(owner, query(config, window[0], window[1]));
		TimeSavedReport.Config clean = TimeSavedReport.Config.builder()
				.range(config.getRange() == null ? TimeSavedReport.Range.THIS_MONTH : config.getRange())
				.from(config.getRange() == TimeSavedReport.Range.CUSTOM ? config.getFrom() : null)
				.to(config.getRange() == TimeSavedReport.Range.CUSTOM ? config.getTo() : null)
				.projectIds(filter.projectIds())
				.userIds(filter.userIds())
				.teamIds(filter.teamIds())
				.tags(filter.tags())
				.billable(filter.billable())
				.activities(filter.activities())
				.q(filter.description())
				.approval(filter.approval().isEmpty() ? null : filter.approval())
				.rounding(config.getRounding())
				.roundingIncrement(config.getRounding() == null ? null : config.getRoundingIncrement())
				.groupBy(config.getGroupBy() == null ? TimeReportService.GroupBy.PROJECT : config.getGroupBy())
				.chart(config.getChart() == null ? TimeSavedReport.Chart.BAR : config.getChart())
				.build();
		return clean;
	}

	private List<String> recipients(List<String> ids) {
		Set<String> wanted = new LinkedHashSet<>();
		if (ids != null) {
			ids.stream().filter(id -> id != null && !id.isBlank()).map(String::strip).forEach(wanted::add);
		}
		if (wanted.size() > TimeSavedReport.RECIPIENTS_MAX) {
			throw ApiException.badRequest("error.time.report.tooManyRecipients", TimeSavedReport.RECIPIENTS_MAX);
		}
		if (wanted.isEmpty()) {
			throw ApiException.badRequest("error.time.report.scheduleInvalid");
		}
		Query query = Query.query(Criteria.where("_id").in(wanted).and("active").is(true));
		query.fields().include("_id");
		Set<String> active = new LinkedHashSet<>();
		mongo.query(User.class).as(Document.class).matching(query).all()
				.forEach(user -> active.add(WorkItemDocuments.id(user)));
		if (!active.containsAll(wanted)) {
			throw ApiException.badRequest("error.time.report.recipientUnknown");
		}
		return new ArrayList<>(wanted);
	}

	private TimeSavedReport own(User owner, String id) {
		TimeSavedReport report = mongo.findById(id, TimeSavedReport.class);
		if (report == null || !report.getOwnerId().equals(owner.getId())) {
			throw ApiException.notFound("savedReport");
		}
		return report;
	}

	private ZoneId zoneOf(User user) {
		return UserZones.of(user, settings.get());
	}

	private static String name(String raw) {
		String name = raw == null ? "" : raw.strip();
		if (name.isEmpty() || name.length() > TimeSavedReport.NAME_MAX) {
			throw ApiException.badRequest("error.time.report.nameInvalid", TimeSavedReport.NAME_MAX);
		}
		return name;
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
