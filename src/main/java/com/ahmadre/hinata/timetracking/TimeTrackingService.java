package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimeRanges;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Logged time on issues: who may read and change which entries, what a valid
 * entry is, and the one derived number that depends on them —
 * {@code Issue.spentMinutes}.
 *
 * <p>Every read and write here is scoped by the caller: entries of an issue are
 * visible to the issue's project members (the same rule as the issue itself),
 * an entry is edited or deleted by its owner, by a lead of its project, or by
 * an admin, and a timesheet shows a user their own hours unless they are an
 * admin. REST and MCP both go through here, so there is exactly one place the
 * rule lives.
 */
@Service
@RequiredArgsConstructor
public class TimeTrackingService {

	/** The array-shaped per-issue list is capped; the paged route has no such limit. */
	public static final int LIST_CAP = 200;
	/** Largest page the paged route hands out. */
	public static final int PAGE_MAX = 100;
	/**
	 * Highest page index a caller may ask for.
	 *
	 * <p>The offset is {@code page * size} and the driver takes a 32-bit skip, so
	 * a page index in the tens of millions overflows it into a negative number
	 * and the request comes back as a 500 with a stack trace on disk — one cheap
	 * GET each, repeatable by anyone signed in. Ten thousand pages of a hundred
	 * is a million entries deep, which nobody reaches by scrolling.
	 */
	public static final int PAGE_INDEX_MAX = 10_000;
	/** Longest a single entry may be: one day. */
	public static final int MAX_MINUTES = 24 * 60;
	/** How far back an entry may be dated, in days. */
	public static final int MAX_DAYS_BACK = 365;
	/** Longest timesheet range, in days. */
	public static final int MAX_RANGE_DAYS = 92;

	private static final String DEFAULT_ACTIVITY = "Development";

	/**
	 * How the personal "Time" list is ordered: the newest day first, within a day
	 * the latest start first, and {@code _id} to break the remaining ties. Entries
	 * with no start sort last within their day, which is where a plain duration
	 * belongs — it did not happen at a time.
	 */
	private static final Sort ENTRIES_NEWEST_FIRST = Sort.by(Sort.Order.desc("date"),
			Sort.Order.desc("startedAt"), Sort.Order.desc("_id"));

	private final WorkItemRepository workItems;
	private final IssueService issues;
	private final ProjectService projects;
	private final ProjectReach projectReach;
	private final UserRepository users;
	private final MongoTemplate mongo;
	private final AuditService audit;
	private final SettingsService settings;
	private final TimeTrackingSettings policy;
	private final Clock clock;

	// --- shapes ------------------------------------------------------------

	/**
	 * What a caller may say when logging time. Everything is optional except
	 * that a duration has to come from somewhere: either {@code durationMinutes}
	 * or a complete {@code startedAt}/{@code endedAt} pair, which then wins and
	 * also supplies the day when none is given.
	 */
	public record NewWorkItem(Integer durationMinutes, LocalDate date, String activityType,
			String description, Instant startedAt, Instant endedAt, List<String> tags,
			Boolean billable) {
	}

	/**
	 * A partial edit. A null field means "leave it"; the two {@code *Set} flags
	 * carry the difference between an absent {@code startedAt} and an explicit
	 * null, which clears it.
	 */
	public record WorkItemPatch(Integer durationMinutes, LocalDate date, String activityType,
			String description, boolean startedAtSet, Instant startedAt, boolean endedAtSet,
			Instant endedAt, List<String> tags, Boolean billable) {
	}

	public record TimesheetRow(String userId, String projectId, Map<LocalDate, Integer> minutesPerDay,
			int totalMinutes) {
	}

	// --- reads -------------------------------------------------------------

	/** The issue behind an id or readable id, if {@code user} may see it (403 otherwise). */
	public Issue resolveIssue(String issueIdOrKey, User user) {
		return issues.getForUser(issueIdOrKey, user);
	}

	/** An issue's entries, newest first, capped at {@link #LIST_CAP}. */
	public List<WorkItem> list(String issueIdOrKey, User user) {
		Issue issue = resolveIssue(issueIdOrKey, user);
		return workItems.findByIssueId(issue.getId(),
				PageRequest.of(0, LIST_CAP, WorkItemRepository.NEWEST_FIRST)).getContent();
	}

	/** One page of an issue's entries, newest first; the size is clamped to {@link #PAGE_MAX}. */
	public Page<WorkItem> page(String issueIdOrKey, int page, int size, User user) {
		Issue issue = resolveIssue(issueIdOrKey, user);
		return workItems.findByIssueId(issue.getId(), PageRequest.of(Math.max(page, 0),
				Math.clamp(size, 1, PAGE_MAX), WorkItemRepository.NEWEST_FIRST));
	}

	// --- writes ------------------------------------------------------------

	/**
	 * Logs time on an issue for {@code user}, who must be a member of its
	 * project. {@code source} says how the entry came about (app, MCP, smart
	 * commit); the entry is always owned by {@code user}.
	 */
	public WorkItem add(String issueIdOrKey, NewWorkItem draft, WorkItem.Source source, User user) {
		// One builder, not two. The two routes differ only in where the placement
		// comes from — a URL here, a body there — and everything after that is the
		// same rules, the same fourteen fields and the same write gate. Kept apart,
		// the next field the epic adds (stage 6's lock date, stage 7's approval
		// state) has to be added twice, and the one that gets missed is a route
		// that silently drops it.
		return create(new NewEntry(null, issueIdOrKey, draft.durationMinutes(), draft.date(),
				draft.activityType(), draft.description(), draft.startedAt(), draft.endedAt(),
				draft.tags(), draft.billable()), source, user);
	}

	/**
	 * Edits an entry. The owner may always; a lead of the entry's project or an
	 * admin may too, and that is audited because it touches someone else's
	 * record of their own work.
	 */
	public WorkItem update(String workItemId, WorkItemPatch patch, User user) {
		WorkItem item = workItems.findById(workItemId)
				.orElseThrow(() -> ApiException.notFound("workItem"));
		boolean own = isOwner(item, user);
		// The entry as it stands, kept whole, because the patch is applied in place.
		WorkItem before = item.toBuilder().build();
		// The gate twice, and deliberately. First on the entry as it stands, so
		// someone with no business touching it is told exactly that instead of
		// being walked through validation of a change that was never theirs to
		// make. Then, below, on the change itself — from stage 6 a lock date or an
		// approval covers the day an entry is moving off as much as the day it is
		// moving to, and only the second call can see both.
		assertWritable(before, before, user);
		if (patch.startedAtSet()) {
			item.setStartedAt(patch.startedAt());
		}
		if (patch.endedAtSet()) {
			item.setEndedAt(patch.endedAt());
		}
		boolean intervalTouched = patch.startedAtSet() || patch.endedAtSet();
		if (intervalTouched && item.getStartedAt() != null && item.getEndedAt() != null) {
			item.setDurationMinutes(resolveDuration(null, item.getStartedAt(), item.getEndedAt()));
		}
		else if (patch.durationMinutes() != null) {
			item.setDurationMinutes(resolveDuration(patch.durationMinutes(), null, null));
		}
		if (patch.date() != null) {
			// Only a day that is being set is checked: an entry older than the
			// window must stay editable in its other fields.
			validateDate(patch.date(), zoneOf(user));
			item.setDate(patch.date());
		}
		if (patch.activityType() != null) {
			item.setActivityType(activityOrDefault(patch.activityType()));
		}
		if (patch.description() != null) {
			item.setDescription(patch.description());
		}
		if (patch.tags() != null) {
			item.setTags(normalizeTags(patch.tags()));
		}
		if (patch.billable() != null) {
			item.setBillable(patch.billable());
		}
		item.setUpdatedAt(clock.instant());
		item.setUpdatedBy(user.getId());
		assertWritable(before, item, user);
		WorkItem saved = workItems.save(item);
		shiftSpentTime(saved.getIssueId(),
				saved.getDurationMinutes() - before.getDurationMinutes());
		if (!own) {
			audit(AuditAction.TIME_ENTRY_UPDATED, saved, user);
		}
		return saved;
	}

	/** Deletes an entry: the owner, a lead of its project, or an admin (audited when foreign). */
	public void delete(String workItemId, User user) {
		delete(workItemId, user, true);
	}

	/**
	 * Deletes one of the caller's <em>own</em> entries only — the MCP tool's
	 * contract, which never elevates a token holder to a lead or an admin.
	 */
	public void deleteOwn(String workItemId, User user) {
		delete(workItemId, user, false);
	}

	private void delete(String workItemId, User user, boolean allowForeign) {
		WorkItem item = workItems.findById(workItemId)
				.orElseThrow(() -> ApiException.notFound("workItem"));
		boolean own = isOwner(item, user);
		// The MCP tool's own contract, narrower than the entry's: holding a token
		// never turns someone into a lead. It sits here rather than in the gate
		// because it is a property of the caller, not of the entry.
		if (!allowForeign && !own) {
			throw ApiException.forbidden("error.time.deleteOwnOnly");
		}
		assertWritable(item, null, user);
		workItems.delete(item);
		shiftSpentTime(item.getIssueId(), -item.getDurationMinutes());
		if (!own) {
			audit(AuditAction.TIME_ENTRY_DELETED, item, user);
		}
	}

	/**
	 * The one gate every change to an entry passes: creating one, editing one,
	 * deleting one — from the app, from an MCP tool, from a smart commit. All
	 * three writes above call it, so a rule added here is a rule that holds
	 * everywhere, and there is no second path to remember.
	 *
	 * <p>Every change <em>a person makes to an entry</em>, that is. The
	 * collection has writers that never come through here, and stage 7 needs to
	 * know which: {@code DeletionService} removes a project's entries outright,
	 * {@code IssueMoveService} re-points {@code projectId}, and
	 * {@code IssueService} detaches {@code issueId} when an issue goes. Those are
	 * cascades of somebody else's decision, not edits, and they will walk over a
	 * lock date and an approved timesheet unless that stage decides otherwise —
	 * which is a decision, not an oversight, and belongs in that ticket.
	 *
	 * <p>Today it states what the service already stated: an entry is written by
	 * the person it belongs to, or by a lead of its project or an administrator.
	 * That is deliberately all it does — stage 6 hangs the lock date on it and
	 * stage 7 the approvals, and both of those need to see the entry on <em>both
	 * sides</em> of the change, which is what {@code before} and {@code after}
	 * are for. An edit that moves an entry off a locked day is as much a write to
	 * that day as one that moves an entry onto it.
	 *
	 * @param before the entry as stored, or null when one is being created
	 * @param after  the entry as it will be stored, or null when one is being deleted
	 * @param actor  who is making the change
	 */
	void assertWritable(WorkItem before, WorkItem after, User actor) {
		// The entry whose ownership decides: the one that exists. On a create
		// that is the entry being written, and the rule is the same one — you
		// write your own, or you are a lead of its project or an administrator.
		// Stating it once means a future on-behalf-of create is judged by the
		// rule rather than by whether its call site remembered to.
		WorkItem subject = before != null ? before : after;
		if (subject == null) {
			return;
		}
		if (isOwner(subject, actor) || canManageForeign(subject, actor)) {
			return;
		}
		throw ApiException.forbidden(
				after == null ? "error.time.deleteOwnOnly" : "error.time.editOwnOnly");
	}

	/** Whether {@code user} may edit or delete an entry that is not their own: admin or project lead. */
	public boolean canManageForeign(WorkItem item, User user) {
		if (user.isAdmin()) {
			return true;
		}
		if (item.getProjectId() == null) {
			return false;
		}
		return projects.findOptional(item.getProjectId())
				.map(project -> projects.isLeadOrAdmin(project, user))
				.orElse(false);
	}

	private static boolean isOwner(WorkItem item, User user) {
		return item.getUserId() != null && item.getUserId().equals(user.getId());
	}

	private void audit(AuditAction action, WorkItem item, User actor) {
		User owner = item.getUserId() == null ? null : users.findById(item.getUserId()).orElse(null);
		AuditService.Entry entry = audit.event(action).actor(actor)
				.meta("workItem", item.getId())
				.meta("issue", item.getIssueId())
				.meta("project", item.getProjectId())
				.meta("owner", item.getUserId())
				.meta("minutes", String.valueOf(item.getDurationMinutes()))
				.meta("date", String.valueOf(item.getDate()));
		if (owner != null) {
			entry.target(owner);
		}
		else if (item.getUserId() != null) {
			entry.target(item.getUserId(), item.getUserId());
		}
		entry.log();
	}

	// --- entries that stand on their own ---------------------------------------

	/**
	 * A standalone entry: like {@link NewWorkItem}, but it carries its own
	 * placement rather than inheriting one from an issue in the URL.
	 *
	 * <p>Both references are optional and mean three different things. With an
	 * issue, the entry is work on that issue. With a project and no issue, it is
	 * project time that no ticket covers — a meeting, a review. With neither it
	 * is unfiled: still the person's own record of their day, visible to them and
	 * to an administrator and to nobody else. The alternative — forcing a
	 * placeholder issue — is how time tracking stops being used.
	 */
	public record NewEntry(String projectId, String issueId, Integer durationMinutes, LocalDate date,
			String activityType, String description, Instant startedAt, Instant endedAt,
			List<String> tags, Boolean billable) {
	}

	/** What narrows the caller's own list. Every field is optional; the owner never is. */
	public record EntryFilter(LocalDate from, LocalDate to, String projectId, String query) {
	}

	/**
	 * Files one entry for {@code user}, placing it wherever the draft says.
	 *
	 * <p>The placement is checked before anything is written, and checked against
	 * what the caller may reach rather than against what they sent: an issue is
	 * resolved through {@link IssueService#getForUser} (so an invisible one is
	 * indistinguishable from a missing one), a project through
	 * {@link ProjectReach}. An issue always supplies its own project — a body
	 * naming both has to agree with itself, or the entry would be filed under a
	 * project the issue is not in and every report would disagree with the issue
	 * page.
	 */
	public WorkItem create(NewEntry draft, WorkItem.Source source, User user) {
		// Placement first, then the body. The order is load-bearing on the 1.x
		// route this now also serves: `POST /issues/{id}/work-items` has always
		// answered 403 for an issue the caller cannot see, whatever else was
		// wrong with the request, and the published 10.3.3 app is entitled to
		// that answer. Checking the duration first would tell an unauthorised
		// caller less — the better property in the abstract, and not one worth
		// changing a frozen route's behaviour to acquire.
		Placement placement = resolvePlacement(draft.projectId(), draft.issueId(), user);
		ZoneId zone = zoneOf(user);
		Instant start = stored(draft.startedAt());
		Instant end = stored(draft.endedAt());
		int minutes = resolveDuration(draft.durationMinutes(), start, end);
		LocalDate date = draft.date() != null ? draft.date()
				: LocalDate.ofInstant(start != null ? start : clock.instant(), zone);
		validateDate(date, zone);
		WorkItem item = WorkItem.builder()
				.issueId(placement.issueId())
				.projectId(placement.projectId())
				.userId(user.getId())
				.date(date)
				.durationMinutes(minutes)
				.activityType(activityOrDefault(draft.activityType()))
				.description(draft.description())
				.startedAt(start)
				.endedAt(end)
				.billable(billableOf(draft.billable()))
				.tags(normalizeTags(draft.tags()))
				.source(source == null ? WorkItem.Source.APP : source)
				.build();
		assertWritable(null, item, user);
		WorkItem saved = workItems.save(item);
		shiftSpentTime(saved.getIssueId(), saved.getDurationMinutes());
		return saved;
	}

	/** Where an entry sits, after the caller's reach has been checked. */
	public record Placement(String projectId, String issueId) {
	}

	/**
	 * Settles project and issue together, because they constrain each other.
	 * Public because the timer files its entry through {@link #insertTimed} and
	 * has to have answered the same question at start time.
	 */
	public Placement resolvePlacement(String projectId, String issueId, User user) {
		if (issueId != null) {
			Issue issue = resolveIssue(issueId, user);
			if (projectId != null && !projectId.equals(issue.getProjectId())) {
				throw ApiException.badRequest("error.time.issueProjectMismatch");
			}
			return new Placement(issue.getProjectId(), issue.getId());
		}
		if (projectId != null && !projectReach.canSee(projectId, user)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		return new Placement(projectId, null);
	}

	/**
	 * Inserts an entry that already knows its own id — the stopped timer's.
	 *
	 * <p>{@code insert}, not {@code save}: Spring Data upserts a document that
	 * carries an id, which would make a second stop overwrite the first entry
	 * instead of colliding with it. The collision is the point, so the caller
	 * sees {@link org.springframework.dao.DuplicateKeyException} and reads it as
	 * "already stopped". Nothing here is retried, and the counter is only moved
	 * once the insert has actually happened.
	 */
	public WorkItem insertTimed(WorkItem item, User user) {
		assertWritable(null, item, user);
		WorkItem saved = workItems.insert(item);
		shiftSpentTime(saved.getIssueId(), saved.getDurationMinutes());
		return saved;
	}

	/**
	 * One page of the caller's <em>own</em> entries, newest first.
	 *
	 * <p>Own, with no way to ask otherwise: this is the list behind the "Time"
	 * page, and R2 of the epic makes a person's entries their own by default.
	 * Reading somebody else's is what the timesheet and the reports are for, each
	 * with its own rule. So the owner is not a filter here, it is the query.
	 *
	 * <p>The sort carries {@code _id} as a tiebreaker for the same reason the
	 * per-issue list does: without it two entries on the same day may swap places
	 * between page one and page two, and the one that moves is either shown twice
	 * or not at all.
	 */
	public Page<WorkItem> entries(EntryFilter filter, int page, int size, User user) {
		LocalDate from = filter.from();
		LocalDate to = filter.to();
		if (from != null && to != null && from.isAfter(to)) {
			// Its own key, not the timesheet's: that one reads "…and at most 92
			// days", a cap this route does not have, and a message that names a
			// limit the caller did not hit is a message that sends them looking.
			throw ApiException.badRequest("error.time.rangeNotAscending");
		}
		Criteria criteria = Criteria.where("userId").is(user.getId());
		if (from != null && to != null) {
			criteria = criteria.and("date").gte(from).lte(to);
		}
		else if (from != null) {
			criteria = criteria.and("date").gte(from);
		}
		else if (to != null) {
			criteria = criteria.and("date").lte(to);
		}
		if (filter.projectId() != null) {
			// No visibility check: these are the caller's own entries either way,
			// so an unreachable project can only ever narrow the answer to
			// nothing. Checking would cost a project read per list request to
			// refuse a query that already refuses itself.
			criteria = criteria.and("projectId").is(filter.projectId());
		}
		String text = filter.query() == null ? null : filter.query().trim();
		if (text != null && !text.isEmpty()) {
			// Quoted, so a description search cannot smuggle a regular expression
			// into the query — ".*" is a search term here, and a catastrophically
			// backtracking pattern is not something a caller gets to compile on
			// the database's time.
			criteria = criteria.and("description")
					.regex(Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE));
		}
		Criteria matched = criteria;
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX), ENTRIES_NEWEST_FIRST);
		List<WorkItem> content = mongo.find(Query.query(matched).with(pageable), WorkItem.class);
		// The count runs without skip/limit, and only when the page could not
		// have been the whole answer — PageableExecutionUtils skips it for a
		// first page that came back short.
		return PageableExecutionUtils.getPage(content, pageable,
				() -> mongo.count(Query.query(matched), WorkItem.class));
	}

	/**
	 * The caller's other entries on the same day that share time with this one.
	 *
	 * <p>A warning, never a refusal. Two entries that overlap are usually a
	 * mistake and occasionally the truth — a call taken during other work, a
	 * timer left running while something else was logged by hand — and the
	 * product that decides which is which for the person is the product they
	 * stop telling the truth to.
	 *
	 * <p>Only entries with both instants take part: a plain duration on a day
	 * says nothing about which hours it occupied, so it can neither overlap nor
	 * be overlapped. And only the entry's own day is examined, which is a stated
	 * limit rather than an oversight — an entry that runs past midnight will not
	 * be matched against the next morning's, and paying for a two-day scan on
	 * every save to catch that is not worth it.
	 *
	 * <p>Answered for {@code actor} and nobody else. A lead may legitimately edit
	 * a member's entry, and this list is keyed on the entry's <em>owner</em> — so
	 * without the check below, one trivial edit would hand the lead the ids of
	 * every timed entry that person filed that day, in projects the lead cannot
	 * see and unfiled ones included. That is precisely what R2 makes private, and
	 * the advice is worthless to a lead anyway: they are not the person whose day
	 * has a clash in it.
	 */
	public List<String> overlapsOf(WorkItem item, User actor) {
		if (item.getStartedAt() == null || item.getEndedAt() == null
				|| item.getUserId() == null || item.getDate() == null
				|| !isOwner(item, actor)) {
			return List.of();
		}
		Query query = Query.query(Criteria.where("userId").is(item.getUserId())
				.and("date").is(item.getDate())
				.and("startedAt").ne(null)
				.and("endedAt").ne(null));
		// A day of one person's entries is a handful; the cap is there so that a
		// pathological day cannot turn one save into an unbounded read.
		query.limit(LIST_CAP);
		List<String> overlapping = new ArrayList<>();
		for (WorkItem other : mongo.find(query, WorkItem.class)) {
			if (other.getId() != null && other.getId().equals(item.getId())) {
				continue;
			}
			if (TimeRanges.overlaps(item.getStartedAt(), item.getEndedAt(),
					other.getStartedAt(), other.getEndedAt())) {
				overlapping.add(other.getId());
			}
		}
		return overlapping;
	}

	/**
	 * What {@code billable} becomes when the request does not say.
	 *
	 * <p>Every write path goes through here, because the flag cannot be
	 * reconstructed afterwards: an entry written while the policy said "billable
	 * by default" and one written while it said otherwise are indistinguishable
	 * a month later except by what was stored at the time. That includes the 1.x
	 * routes — the policy ships as {@code false}, so an instance that has never
	 * touched it sees no change, and an administrator who did touch it meant it.
	 */
	private boolean billableOf(Boolean given) {
		return given != null ? given : policy.defaultBillable();
	}

	// --- the derived counter -------------------------------------------------

	/**
	 * Moves {@code Issue.spentMinutes} by what one entry just changed.
	 *
	 * <p>{@code $inc}, not a recomputed {@code $set}. The counter is the sum of
	 * the issue's entries, and reading that sum and then writing it back is a
	 * lost update the moment two people log time on the same issue at once: one
	 * of them reads the total before the other's entry exists and writes it
	 * afterwards, and the minutes in between are simply gone. Twelve concurrent
	 * entries of ten minutes landed as ninety. An increment carries only this
	 * writer's own delta, so the arithmetic is Mongo's and every writer's
	 * contribution survives — which makes the counter more exactly the sum of
	 * the entries, not less.
	 *
	 * <p>A delta of zero writes nothing at all: correcting a note is not a
	 * change to the hours, and stamping {@code updatedAt} for it would jump the
	 * issue to the top of every recently-changed list.
	 */
	private void shiftSpentTime(String issueId, int minutes) {
		if (issueId == null || minutes == 0) {
			return;
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issueId)),
				new Update().inc("spentMinutes", minutes).currentDate("updatedAt"), Issue.class);
	}

	/**
	 * Recomputes {@code Issue.spentMinutes} from the entries themselves.
	 *
	 * <p>The counter is maintained incrementally on the write paths, so this is
	 * the repair, not the routine: it is what puts an issue right after entries
	 * were written around the service — a restore, a fixture, a future importer
	 * — and what the tests use to state the invariant the increments maintain.
	 */
	/**
	 * Puts {@code Issue.spentMinutes} right <em>if nobody else is writing it</em>.
	 *
	 * <p>Same arithmetic as {@link #syncSpentTime}, and a different promise. That
	 * one is the repair: called when the counter is known to be wrong and nothing
	 * else is touching the issue. This one runs on a live path — a stop that
	 * collided with another stop — where somebody else may be logging time on the
	 * same issue at that moment, and a plain recompute would read the sum, be
	 * overtaken by their {@code $inc}, and write their minutes away. So the write
	 * is conditional on the counter still holding what was read: if it moved,
	 * their increment is the newer truth and this stands down.
	 *
	 * @return whether the counter was corrected
	 */
	boolean reconcileSpentTime(String issueId) {
		if (issueId == null) {
			return false;
		}
		Document stored = mongo.findOne(Query.query(Criteria.where("_id").is(issueId))
				.limit(1), Document.class, "issues");
		if (stored == null) {
			return false;
		}
		int observed = stored.get("spentMinutes") instanceof Number number ? number.intValue() : 0;
		int total = sumOfEntries(issueId);
		if (total == observed) {
			return false;
		}
		return mongo.updateFirst(
				Query.query(Criteria.where("_id").is(issueId).and("spentMinutes").is(observed)),
				new Update().set("spentMinutes", total).currentDate("updatedAt"),
				Issue.class).getModifiedCount() > 0;
	}

	/** What an issue's entries add up to, straight from the collection. */
	private int sumOfEntries(String issueId) {
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("issueId").is(issueId)),
				Aggregation.group().sum("durationMinutes").as("total"));
		Document row = mongo.aggregate(aggregation, WorkItem.class, Document.class)
				.getUniqueMappedResult();
		return row != null && row.get("total") instanceof Number number ? number.intValue() : 0;
	}

	public void syncSpentTime(String issueId) {
		if (issueId == null) {
			return;
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issueId)),
				new Update().set("spentMinutes", sumOfEntries(issueId)).currentDate("updatedAt"),
				Issue.class);
	}

	// --- timesheet -------------------------------------------------------------

	/**
	 * Timesheet matrix: per user+project row, minutes per day in the range.
	 *
	 * <p>Scoped by {@code requester}: an admin may filter by any user and any
	 * project or by nothing at all; everyone else gets their own rows — a foreign
	 * {@code userId} is refused, not silently replaced — and may narrow them to
	 * a project they can see. Both filters are applied together, so a project
	 * filter can never widen a user filter.
	 */
	public List<TimesheetRow> timesheet(LocalDate from, LocalDate to, String userId, String projectId,
			User requester) {
		// Counted, not offset: a year of +999999999 binds fine through
		// ISO_LOCAL_DATE, and `from.plusDays(...)` on a date near LocalDate.MAX
		// throws before the guard can answer — one cheap GET for one 500 and one
		// stack trace on disk, repeatable.
		if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
			throw ApiException.badRequest("error.time.invalidRange");
		}
		String effectiveUser = userId;
		if (!requester.isAdmin()) {
			if (userId == null) {
				effectiveUser = requester.getId();
			}
			else if (!userId.equals(requester.getId())) {
				throw ApiException.forbidden("error.accessDenied");
			}
		}
		if (projectId != null && !projectReach.canSee(projectId, requester)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		Criteria criteria = Criteria.where("date").gte(from).lte(to);
		if (effectiveUser != null) {
			criteria = criteria.and("userId").is(effectiveUser);
		}
		if (projectId != null) {
			criteria = criteria.and("projectId").is(projectId);
		}
		return rowsOf(criteria);
	}

	/**
	 * The matrix itself: Mongo sums the minutes, this assembles the rows.
	 *
	 * <p>Summing here rather than in Java is what keeps the endpoint usable at
	 * the 92 days it allows. An unfiltered quarter in a two-hundred-person
	 * instance matches on the order of eighty thousand entries and answers with
	 * a few thousand rows; loading the entries to add them up meant carrying all
	 * eighty thousand as objects to emit the same numbers. What comes back now
	 * is one figure per person, project and day — the shape of the answer, not
	 * the shape of the data behind it — and the JSON is unchanged, which the
	 * published app depends on.
	 *
	 * <p>The day is grouped as a formatted string rather than the stored value,
	 * so an entry whose date somehow carries a time of day still lands on its
	 * own day instead of opening a second bucket. {@code $dateToString} reads
	 * UTC by default, which is the zone {@code date} is written in.
	 */
	private List<TimesheetRow> rowsOf(Criteria criteria) {
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(criteria),
				Aggregation.project("userId", "projectId", "durationMinutes")
						.and(DateOperators.dateOf("date").toString("%Y-%m-%d")).as("day"),
				Aggregation.group("userId", "projectId", "day")
						.sum("durationMinutes").as("minutes"));
		// A record key rather than "userId|projectId": either side may be null
		// (legacy entries carry no user, detached entries may carry no project)
		// and a null must group with nulls, not with the literal text "null".
		record RowKey(String userId, String projectId) {
		}
		Map<RowKey, Map<LocalDate, Integer>> rowsByKey = new LinkedHashMap<>();
		for (Document group : mongo.aggregate(aggregation, WorkItem.class, Document.class)) {
			Document key = group.get("_id", Document.class);
			String day = key == null ? null : key.getString("day");
			if (day == null) {
				continue; // an entry with no date at all belongs to no column
			}
			int minutes = group.get("minutes") instanceof Number sum ? sum.intValue() : 0;
			rowsByKey.computeIfAbsent(
					new RowKey(key.getString("userId"), key.getString("projectId")),
					unused -> new TreeMap<>())
					.merge(LocalDate.parse(day), minutes, Integer::sum);
		}
		List<TimesheetRow> rows = new ArrayList<>();
		rowsByKey.forEach((key, perDay) -> rows.add(new TimesheetRow(key.userId(), key.projectId(),
				perDay, perDay.values().stream().mapToInt(Integer::intValue).sum())));
		rows.sort(Comparator.comparing(TimesheetRow::userId, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(TimesheetRow::projectId, Comparator.nullsLast(Comparator.naturalOrder())));
		return rows;
	}

	// --- rules -------------------------------------------------------------------

	/** The zone "today" and a timer's day are read in for this user. */
	public ZoneId zoneOf(User user) {
		return UserZones.of(user, settings.get());
	}

	/**
	 * A complete start/end pair defines the duration and must span between one
	 * minute and one day; otherwise the given minutes must, and they must be
	 * given.
	 */
	static int resolveDuration(Integer given, Instant start, Instant end) {
		if (start != null && end != null) {
			long minutes = Duration.between(start, end).toMinutes();
			if (minutes < 1 || minutes > MAX_MINUTES) {
				throw ApiException.badRequest("error.time.invalidDuration");
			}
			return (int) minutes;
		}
		if (given == null || given < 1 || given > MAX_MINUTES) {
			throw ApiException.badRequest("error.time.invalidDuration");
		}
		return given;
	}

	/**
	 * An instant as MongoDB will hold it: whole milliseconds.
	 *
	 * <p>{@code Clock.systemUTC()} reads microseconds on this platform and BSON
	 * stores milliseconds, so an instant that is not truncated on the way in
	 * comes back different on the way out. The visible cost is small — a timer
	 * whose {@code startedAt} shifts by a fraction of a millisecond when the page
	 * is reloaded — but "what the server just told me" and "what the server
	 * stored" disagreeing is the kind of difference that is only ever noticed by
	 * something downstream comparing them.
	 */
	static Instant stored(Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
	}

	/** Not after today and not more than a year back — today being the user's today, not the server's. */
	private void validateDate(LocalDate date, ZoneId zone) {
		LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
		if (date.isAfter(today)) {
			throw ApiException.badRequest("error.time.dateInFuture");
		}
		if (date.isBefore(today.minusDays(MAX_DAYS_BACK))) {
			throw ApiException.badRequest("error.time.dateTooOld");
		}
	}

	/** Package-private: the timer files entries too, and must not carry a second default. */
	static String activityOrDefault(String activityType) {
		return activityType == null || activityType.isBlank() ? DEFAULT_ACTIVITY : activityType.trim();
	}

	/** Trimmed, no blanks, no duplicates, order kept. Size limits are the request's job. */
	static List<String> normalizeTags(List<String> tags) {
		if (tags == null) {
			return new ArrayList<>();
		}
		LinkedHashSet<String> cleaned = new LinkedHashSet<>();
		for (String tag : tags) {
			if (tag != null && !tag.isBlank()) {
				cleaned.add(tag.trim());
			}
		}
		return new ArrayList<>(cleaned);
	}
}
