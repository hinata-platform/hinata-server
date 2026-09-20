package com.ahmadre.hinata.template;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueLink;
import com.ahmadre.hinata.issue.IssueLinkType;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.timetracking.ProjectTimeSettings;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AccumulatorOperators;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ArrayOperators;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turning one project into a second project: the same plan, none of the history.
 *
 * <p><b>What a copy is.</b> The structure — workflow, labels, resolved states, colour — and every
 * issue that makes up the plan, with its sub-tasks to full depth, its dependencies and its links
 * inside the project, and the relative deadlines of stage 2. Optionally the members, the
 * attachments, the project's time settings and its own board.
 *
 * <p><b>What a copy is not.</b> Comments, activity, recorded time, watchers, reporters, states,
 * sprints, the Git connection and the mail inbox. Those are the original's history and the
 * original's wiring; a copy that inherited them would claim things that never happened to it, and
 * in the case of Git it would carry an encrypted token and a webhook that belong to exactly one
 * project.
 *
 * <p><b>Why two lists.</b> {@link #CARRIED} and {@link #LEFT_BEHIND} name every field of
 * {@code Project} and of {@code Issue}, and a reflection test breaks the build when a new field
 * appears in neither. Without it a copy silently loses a field six months from now, and nobody
 * finds out until the field mattered.
 *
 * <p><b>All or nothing.</b> Anything that goes wrong takes the half-built project, its issues, its
 * links and its copied files back out again. A half copied project is worse than no copy: it looks
 * like a plan and is missing the parts nobody checked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectCopyService {

	/** The most issues one copy carries. Beyond this the request is refused, not truncated. */
	public static final int MAX_ISSUES = 500;

	/** How many issue documents go into the database per round trip. */
	private static final int BATCH = 200;

	/** The longest name a copy may carry, matching the bound on the REST body. */
	private static final int MAX_NAME_CHARS = 120;

	/**
	 * How many people a copy tells about itself. Past this the notice is skipped: a copy is one
	 * request, and a mail and a push each to several hundred people is not what somebody pressing
	 * "copy" is asking for.
	 */
	private static final int MAX_NOTIFIED_MEMBERS = 50;

	/** The same file budget the issue clone holds to, for the same reasons. */
	static final int MAX_COPIED_FILES = 50;
	static final long MAX_COPIED_BYTES = 100L * 1024 * 1024;

	/**
	 * Fields of {@code Project} and {@code Issue} a copy can end up carrying — some verbatim, some
	 * behind one of the switches, some rewritten to point at the copy.
	 */
	static final List<String> CARRIED = List.of(
			// --- Project: the plan and how it is presented ---
			"key", "name", "description", "color", "avatarUrl", "workflowStates",
			"resolvedStates", "labels", "leadId", "leadIds", "memberIds", "eventDate",
			"workdayCalendarId", "template",
			// --- Issue: the work itself ---
			"projectId", "title", "description", "descriptionDoc", "type", "priority", "tags",
			"parentId", "estimateMinutes", "storyPoints", "startDate", "dueDate",
			"startOffset", "dueOffset", "attachments",
			"dependsOnIds", "state", "rank", "numberInProject", "readableId", "id",
			"searchText", "createdAt", "updatedAt");

	/**
	 * Fields a copy never inherits, each for its own reason:
	 *
	 * <ul>
	 *   <li>{@code git} and {@code extraRepos}: a connection carries an encrypted access token and
	 *       a webhook registered for one repository and one project. Handing them to a second
	 *       project would mean two projects reacting to the same push under one set of
	 *       credentials, so a copy reconnects deliberately or not at all;
	 *   <li>{@code reporterId} and the e-mail-ingest quartet ({@code reporterEmail},
	 *       {@code inboundMessageId}, {@code inboundSubject}, {@code ingestConnectionId}): the
	 *       copy did not arrive by mail, and duplicating a message id would break the dedupe that
	 *       stops a re-polled mail becoming a second ticket;
	 *   <li>{@code watcherIds}: a subscription is one person's decision about one ticket;
	 *   <li>{@code sprintId}: sprints hang off the board, not the project, and a sprint without
	 *       its dates is not a sprint;
	 *   <li>history and progress — {@code spentMinutes}, {@code resolvedAt}, {@code archived} and
	 *       {@code archivedAt}, {@code dueReminderFor}, {@code formerReadableIds}: none of it
	 *       happened to the copy. {@code archived} covers both entities: a copy of an archived
	 *       project is refused outright, and archived issues never join the plan;
	 *   <li>{@code issueCounter}: raised to match what the copy actually wrote, once the issues
	 *       are in, rather than inherited from a project with a different number of them;
	 *   <li>{@code assigneeId} / {@code assigneeIds}: a copy is work to <em>do</em>, and putting
	 *       somebody's name on it is a claim nobody made. It would also be their first news of
	 *       the project: the reminder job mails assignees, so an inherited assignment turns into
	 *       a "due soon" mail about a project they were never told they had joined;
	 *   <li>{@code subtaskCount} / {@code subtaskDoneCount}: never stored, computed per request.
	 * </ul>
	 *
	 * <p>Comments, activity, recorded time and approvals are not fields at all: they are other
	 * collections keyed by issue or project id, and nothing here reads them.
	 */
	static final List<String> LEFT_BEHIND = List.of(
			// --- Project ---
			"git", "extraRepos", "archived", "issueCounter",
			// --- Issue ---
			"formerReadableIds", "reporterId", "reporterEmail", "inboundMessageId",
			"inboundSubject", "ingestConnectionId", "watcherIds", "sprintId", "spentMinutes",
			"resolvedAt", "archivedAt", "dueReminderFor", "assigneeId", "assigneeIds",
			"subtaskCount", "subtaskDoneCount");

	private final ProjectService projects;
	private final MongoTemplate mongo;
	private final StorageService storage;
	private final AuditService audit;
	private final ProjectTemplateSettings settings;
	private final HolidayCalendars calendars;
	private final ProjectCopyLimiter limiter;

	/** What the copy sheet lets a caller decide. */
	public record Options(String name, String key, LocalDate eventDate, boolean includeMembers,
			boolean includeAttachments, boolean includeTimeSettings, boolean includeBoard,
			boolean asTemplate) {
	}

	/** What a copy turned out to be, for the sheet's toast and for the audit record. */
	public record Result(Project project, int issuesCopied, int subtasksCopied,
			int attachmentsCopied, int deadlinesSet) {
	}

	/** What a copy would be, so the sheet can say it before anybody presses the button. */
	public record Scope(int issues, int subtasks, int attachments, long attachmentBytes,
			String suggestedKey, boolean withinLimit) {
	}

	/**
	 * What copying this project would involve — the numbers the sheet prints under its switches,
	 * computed by the server rather than guessed by the app.
	 */
	public Scope scopeOf(String projectId, User user) {
		requireModule();
		Project source = projects.get(projectId);
		projects.assertMember(source, user);
		// Counted in the database rather than in the heap. This is a read route any member can
		// call on every sheet open, and loading a large project's issues — Lexical documents and
		// all — to produce four integers is the kind of cost nobody sees until it is an outage.
		Criteria plan = Criteria.where("projectId").is(source.getId()).and("archived").ne(true);
		int issues = (int) mongo.count(Query.query(plan), Issue.class);
		int subtasks = (int) mongo.count(
				Query.query(Criteria.where("projectId").is(source.getId())
						.and("archived").ne(true).and("parentId").ne(null)), Issue.class);
		// The files are the one pair a count cannot answer, so they are summed where they are.
		Aggregation totals = Aggregation.newAggregation(
				Aggregation.match(plan),
				Aggregation.project()
						.and(ArrayOperators.Size.lengthOfArray(
								ConditionalOperators.ifNull("attachments").then(List.of())))
								.as("fileCount")
						.and(AccumulatorOperators.Sum.sumOf("attachments.size")).as("fileBytes"),
				Aggregation.group()
						.sum("fileCount").as("files")
						.sum("fileBytes").as("bytes"));
		Document files = mongo.aggregate(totals, Issue.class, Document.class)
				.getUniqueMappedResult();
		return new Scope(issues, subtasks, number(files, "files"), longNumber(files, "bytes"),
				suggestKey(source), issues <= MAX_ISSUES);
	}

	/** One aggregated number, or zero for a project with no issues at all. */
	private static int number(Document counts, String field) {
		Object value = counts == null ? null : counts.get(field);
		return value instanceof Number n ? n.intValue() : 0;
	}

	private static long longNumber(Document counts, String field) {
		Object value = counts == null ? null : counts.get(field);
		return value instanceof Number n ? n.longValue() : 0L;
	}

	/**
	 * Copies the project.
	 *
	 * <p>Whoever may see the source and may create a project may copy it. Creating a project is
	 * open to every member on this platform — the person who creates one leads it — so being able
	 * to see the original is the binding half, and it is the half that matters: a copy carries the
	 * original's descriptions and attachments, and nobody may obtain those by copying what they
	 * could not read.
	 */
	public Result copy(String sourceId, Options options, User user) {
		requireModule();
		limiter.consume(user);
		Project source = projects.get(sourceId);
		projects.assertMember(source, user);
		if (source.isArchived()) {
			// A project is archived because it is finished, or because it is sitting out a
			// retention period. Either way its content reappearing inside a live project is not
			// a copy of a plan, it is an archive nobody closed.
			throw ApiException.badRequest("error.project.copyArchived");
		}
		// Bounds the controllers used to hold on their own, which left the MCP tool outside them.
		LocalDate eventDate = ProjectScheduleService.checked(options.eventDate());
		String name = checkedName(options.name(), source);

		long total = countPlan(source.getId());
		if (total > MAX_ISSUES) {
			throw ApiException.badRequest("error.project.copyTooLarge", (int) total, MAX_ISSUES);
		}
		// One more than the budget, so a refusal never costs the price of the thing refused.
		List<Issue> plan = planOf(source.getId(), MAX_ISSUES + 1);
		if (plan.size() > MAX_ISSUES) {
			throw ApiException.badRequest("error.project.copyTooLarge", plan.size(), MAX_ISSUES);
		}
		if (options.includeAttachments()) {
			assertWithinFileBudget(plan);
		}

		Project copy = newProject(source, options, name, eventDate, user);
		Trace trace = new Trace();
		Result result;
		try {
			Project created = projects.create(copy, user);
			trace.projectId = created.getId();
			Copied copied = copyIssues(source, created, plan, options, trace);
			copyLinks(source.getId(), copied.idMap(), trace);
			if (options.includeBoard()) {
				copyBoard(source, created, trace);
			}
			if (options.includeTimeSettings()) {
				copyTimeSettings(source.getId(), created.getId(), trace);
			}
			audit.event(AuditAction.PROJECT_COPIED).actor(user)
					.meta("source", source.getKey())
					.meta("copy", created.getKey())
					.meta("issues", String.valueOf(copied.issues()))
					.meta("subtasks", String.valueOf(copied.subtasks()))
					.meta("attachmentsCopied", String.valueOf(trace.objectKeys.size()))
					.meta("members", String.valueOf(options.includeMembers()))
					.meta("attachments", String.valueOf(options.includeAttachments()))
					.meta("timeSettings", String.valueOf(options.includeTimeSettings()))
					.meta("board", String.valueOf(options.includeBoard()))
					.meta("eventDate", options.eventDate() == null
							? "none" : options.eventDate().toString())
					.log();
			result = new Result(created, copied.issues(), copied.subtasks(),
					trace.objectKeys.size(), copied.deadlines());
		}
		catch (RuntimeException failed) {
			// A project that half exists looks like a plan and is missing the parts nobody
			// checked. Everything this call wrote goes back out, files included.
			rollback(trace);
			throw failed;
		}
		// Outside the rollback window on purpose. A bell entry, a mail and a push cannot be
		// unsent, so telling people about a project before it is certainly there means telling
		// them about one that may be removed a moment later, with a deep link that 404s.
		announce(result.project(), user);
		return result;
	}

	/**
	 * Tells the people the copy arrived with. {@code create} sends nothing, which is right for a
	 * project somebody starts empty and wrong for one that lands with a team already in it — the
	 * first thing they would otherwise hear about it is a deadline reminder.
	 *
	 * <p>Bounded, because this is a fan-out a member can ask for twenty times an hour: a copy of
	 * a five-hundred-person project would otherwise be five hundred bell entries, mails and
	 * pushes per request. Above the threshold the copy is silent and says so in the log; whoever
	 * made it can still tell the project who it belongs to.
	 */
	private void announce(Project created, User user) {
		List<String> members = nonNull(created.getMemberIds());
		if (members.size() > MAX_NOTIFIED_MEMBERS) {
			log.info("Copy {} reached {} members; not notifying them individually",
					created.getKey(), members.size());
			return;
		}
		projects.notifyNewMembers(created, Set.of(user.getId()), user);
	}

	// --- the plan -------------------------------------------------------------

	/**
	 * The issues that make up the plan: everything in the project that is not archived.
	 *
	 * <p>Archived issues are the project's history — somebody decided they were done with them —
	 * and a copy is the plan, not the history. Carrying them would also make a copy of a
	 * long-running project mostly consist of work nobody intends to do again.
	 */
	private List<Issue> planOf(String projectId, int cap) {
		Query query = Query.query(Criteria.where("projectId").is(projectId)
				.and("archived").ne(true));
		if (cap > 0) {
			query.limit(cap);
		}
		return mongo.find(query, Issue.class);
	}

	/** How many issues the plan holds, without reading one of them. */
	private long countPlan(String projectId) {
		return mongo.count(Query.query(Criteria.where("projectId").is(projectId)
				.and("archived").ne(true)), Issue.class);
	}

	/** Refuses when the module is off — the second lock, for the callers no interceptor sees. */
	private void requireModule() {
		if (!settings.enabled()) {
			throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
					ProjectTemplateGate.DISABLED_KEY);
		}
	}

	/**
	 * The name the copy gets, held to the same length the REST body is.
	 *
	 * <p>Checked here rather than only on the request record, because the MCP tool builds its
	 * options by hand and would otherwise store a name of any length, which then renders in the
	 * project list of everybody the copy enrolled.
	 */
	private static String checkedName(String name, Project source) {
		if (name != null && !name.isBlank()) {
			String chosen = name.trim();
			if (chosen.length() > MAX_NAME_CHARS) {
				throw ApiException.badRequest("error.project.nameTooLong", MAX_NAME_CHARS);
			}
			return chosen;
		}
		// Nobody named it, so nobody can be refused for the length: a project whose own name is
		// long enough that "… (copy)" overruns the bound gets a shortened one rather than an
		// error about a field the request never filled in.
		String suffixed = source.getName() + " (copy)";
		return suffixed.length() <= MAX_NAME_CHARS
				? suffixed : suffixed.substring(0, MAX_NAME_CHARS);
	}

	/** A project key nobody is using yet, derived from the source's. */
	private String suggestKey(Project source) {
		String base = source.getKey() == null ? "COPY" : source.getKey().toUpperCase(Locale.ROOT);
		// Room for the two digits appended below, inside the ten characters a key may hold.
		String stem = base.length() > 8 ? base.substring(0, 8) : base;
		// Every key that could collide in one round trip. Asking per candidate was up to
		// ninety-eight queries, each a full scan of the key index, on a route the sheet calls
		// every time it opens.
		// Quoted for the *database*, not for java.util.regex: Mongo's PCRE2 understands \Q…\E,
		// and the stem comes from a key that is [A-Z0-9] by construction either way.
		Query taken = Query.query(Criteria.where("key")
				.regex("^" + Pattern.quote(stem) + "\\d{1,2}$", "i"));
		taken.fields().include("key");
		Set<String> used = new LinkedHashSet<>();
		mongo.find(taken, Project.class).forEach(project ->
				used.add(project.getKey() == null ? "" : project.getKey().toUpperCase(Locale.ROOT)));
		for (int suffix = 2; suffix <= 99; suffix++) {
			String candidate = stem + suffix;
			if (!used.contains(candidate)) {
				return candidate;
			}
		}
		// Ninety-eight variants of one key are all taken. Rather than refuse, hand back
		// something certainly free and let whoever is copying rename it.
		return "P" + Instant.now().toEpochMilli() % 1_000_000_000L;
	}

	private Project newProject(Project source, Options options, String name, LocalDate eventDate,
			User user) {
		String key = options.key() == null || options.key().isBlank()
				? suggestKey(source) : options.key().trim().toUpperCase(Locale.ROOT);
		List<String> leads = new ArrayList<>(List.of(user.getId()));
		List<String> members = new ArrayList<>();
		if (options.includeMembers()) {
			// The original's people, and the leads among them stay leads. Whoever copies is a
			// lead of the copy regardless — somebody has to be able to configure it.
			members.addAll(nonNull(source.getMemberIds()));
			for (String lead : nonNull(source.getLeadIds())) {
				if (!leads.contains(lead)) {
					leads.add(lead);
				}
			}
		}
		if (!members.contains(user.getId())) {
			members.add(user.getId());
		}
		return Project.builder()
				.key(key)
				.name(name)
				.description(source.getDescription())
				.color(source.getColor())
				.avatarUrl(source.getAvatarUrl())
				.workflowStates(new ArrayList<>(nonNull(source.getWorkflowStates())))
				.resolvedStates(new ArrayList<>(nonNull(source.getResolvedStates())))
				.labels(new ArrayList<>(nonNull(source.getLabels())))
				.leadId(user.getId())
				.leadIds(leads)
				.memberIds(members)
				.eventDate(eventDate)
				.workdayCalendarId(source.getWorkdayCalendarId())
				.template(options.asTemplate())
				.build();
	}

	// --- the issues -----------------------------------------------------------

	/** What copying the issues produced. */
	private record Copied(Map<String, String> idMap, int issues, int subtasks, int deadlines) {
	}

	/**
	 * Writes the copy's issues, in batches, with their hierarchy and their internal dependencies
	 * pointing at the copy.
	 *
	 * <p>The ids are minted here rather than by the database, because every reference between two
	 * issues — a parent, a dependency, a link — has to be rewritten to the copy, and a mapping
	 * cannot be built from ids that do not exist yet. A dangling reference would point at the
	 * original: the copy would silently depend on somebody else's ticket.
	 */
	private Copied copyIssues(Project source, Project copy, List<Issue> plan, Options options,
			Trace trace) {
		Map<String, String> idMap = new HashMap<>();
		for (Issue issue : plan) {
			idMap.put(issue.getId(), UUID.randomUUID().toString());
		}
		String firstState = copy.workflowStateNames().isEmpty()
				? null : copy.workflowStateNames().get(0);
		WorkdayCalendar calendar = calendars.of(copy);
		List<Issue> ordered = parentsFirst(plan);
		List<Issue> pending = new ArrayList<>(BATCH);
		long number = 0;
		int subtasks = 0;
		int deadlines = 0;
		for (Issue original : ordered) {
			number++;
			String parentId = original.getParentId() == null
					? null : idMap.get(original.getParentId());
			if (original.getParentId() != null && parentId != null) {
				subtasks++;
			}
			List<Issue.Attachment> attachments = options.includeAttachments()
					? copyAttachments(original, copy, trace) : new ArrayList<>();
			// Every deadline is recomputed from the copy's event date rather than carried over.
			// That is the whole point of the feature: the same plan, the new date.
			LocalDate start = RelativeDates.resolve(copy.getEventDate(),
					original.getStartOffset(), calendar);
			LocalDate due = RelativeDates.resolve(copy.getEventDate(),
					original.getDueOffset(), calendar);
			if (due != null || start != null) {
				deadlines++;
			}
			Issue built = Issue.builder()
					.id(idMap.get(original.getId()))
					.projectId(copy.getId())
					.numberInProject(number)
					.readableId(copy.getKey() + "-" + number)
					.title(original.getTitle())
					.description(original.getDescription())
					.descriptionDoc(original.getDescriptionDoc())
					.type(original.getType())
					.priority(original.getPriority())
					// The copy is work to do. An issue that was finished in the original comes
					// back as something to start, not as a second finished issue.
					.state(firstState)
					.tags(new ArrayList<>(nonNull(original.getTags())))
					.parentId(parentId)
					.estimateMinutes(original.getEstimateMinutes())
					.storyPoints(original.getStoryPoints())
					.startOffset(original.getStartOffset())
					.dueOffset(original.getDueOffset())
					// Without an event date the rule travels and the dates stay empty, which is
					// exactly what a template looks like.
					.startDate(start != null ? start : keptDate(original.getStartDate(), original.getStartOffset()))
					.dueDate(due != null ? due : keptDate(original.getDueDate(), original.getDueOffset()))
					// Nobody is assigned. A copy is work to do, and a name on it is a claim
					// nobody made — one the reminder job would mail them about before anybody
					// told them the project existed.
					.assigneeIds(new ArrayList<>())
					.dependsOnIds(remap(original.getDependsOnIds(), idMap))
					.attachments(attachments)
					.rank(original.getRank())
					.build();
			pending.add(built);
			trace.issueIds.add(built.getId());
			if (pending.size() >= BATCH) {
				mongo.insert(pending, Issue.class);
				pending.clear();
			}
		}
		if (!pending.isEmpty()) {
			mongo.insert(pending, Issue.class);
		}
		// So the next issue created by hand continues the numbering rather than colliding with
		// what this just wrote.
		projects.ensureIssueCounterAtLeast(copy.getId(), number);
		return new Copied(idMap, ordered.size(), subtasks, deadlines);
	}

	/**
	 * A date the copy keeps as it stands: one somebody typed, with no rule behind it.
	 *
	 * <p>A date that <em>had</em> a rule is dropped when the copy has no event date to resolve it
	 * against — keeping it would state a deadline the copy never chose, taken from a date in the
	 * original's calendar.
	 */
	private static LocalDate keptDate(LocalDate date, com.ahmadre.hinata.common.RelativeDate offset) {
		return offset == null ? date : null;
	}

	/**
	 * The plan ordered so that no issue is written before its parent.
	 *
	 * <p>{@code validateHierarchy} is not run here — these documents go in as a batch — but the
	 * numbering should still read top down, and an epic that arrives after its children makes
	 * every list in the product look shuffled on the day of the copy.
	 *
	 * <p>A cycle among parents cannot be created through the API and would hang a naive walk, so
	 * whatever is left over after the reachable ones are placed is appended as it comes.
	 */
	private static List<Issue> parentsFirst(List<Issue> plan) {
		Map<String, List<Issue>> childrenOf = new LinkedHashMap<>();
		List<Issue> roots = new ArrayList<>();
		Set<String> ids = new LinkedHashSet<>();
		plan.forEach(issue -> ids.add(issue.getId()));
		for (Issue issue : plan) {
			String parent = issue.getParentId();
			if (parent == null || !ids.contains(parent)) {
				roots.add(issue);
			}
			else {
				childrenOf.computeIfAbsent(parent, key -> new ArrayList<>()).add(issue);
			}
		}
		List<Issue> ordered = new ArrayList<>(plan.size());
		Set<String> placed = new LinkedHashSet<>();
		Deque<Issue> queue = new ArrayDeque<>(roots);
		while (!queue.isEmpty()) {
			Issue next = queue.removeFirst();
			if (!placed.add(next.getId())) {
				continue;
			}
			ordered.add(next);
			childrenOf.getOrDefault(next.getId(), List.of()).forEach(queue::addLast);
		}
		for (Issue issue : plan) {
			if (!placed.contains(issue.getId())) {
				ordered.add(issue);
			}
		}
		return ordered;
	}

	/**
	 * The ids of {@code values} as they are in the copy; anything pointing out of the project is
	 * dropped.
	 *
	 * <p>A dependency on a ticket in another project is a statement about the original's work, not
	 * the copy's, and a copy that inherited it would be waiting on something that knows nothing
	 * about it.
	 */
	private static List<String> remap(List<String> values, Map<String, String> idMap) {
		List<String> mapped = new ArrayList<>();
		for (String value : nonNull(values)) {
			String replacement = idMap.get(value);
			if (replacement != null) {
				mapped.add(replacement);
			}
		}
		return mapped;
	}

	// --- the rest of the project ---------------------------------------------

	/**
	 * The links between two issues of the project, rewritten onto the copies.
	 *
	 * <p>Links that leave the project are dropped for the same reason a dependency is, and
	 * {@code CLONES} links are dropped as well: a clone link says where a ticket came from, which
	 * is history rather than a property of the work.
	 */
	private void copyLinks(String sourceId, Map<String, String> idMap, Trace trace) {
		if (idMap.isEmpty()) {
			return;
		}
		Set<String> sourceIds = idMap.keySet();
		Query query = Query.query(new Criteria().orOperator(
				Criteria.where("sourceId").in(sourceIds),
				Criteria.where("targetId").in(sourceIds)));
		List<IssueLink> copies = new ArrayList<>();
		for (IssueLink link : mongo.find(query, IssueLink.class)) {
			if (link.getType() == IssueLinkType.CLONES) {
				continue;
			}
			String from = idMap.get(link.getSourceId());
			String to = idMap.get(link.getTargetId());
			if (from == null || to == null) {
				continue;
			}
			copies.add(IssueLink.builder()
					.id(UUID.randomUUID().toString())
					.type(link.getType())
					.sourceId(from)
					.targetId(to)
					.createdBy(link.getCreatedBy())
					.build());
		}
		if (copies.isEmpty()) {
			return;
		}
		mongo.insert(copies, IssueLink.class);
		copies.forEach(link -> trace.linkIds.add(link.getId()));
	}

	/**
	 * The project's own board, with its columns.
	 *
	 * <p>Its own: a board spanning several projects belongs to all of them, and adding the copy to
	 * it would change what the other projects' teams see. Sprints do not come along either — they
	 * hang off the board and a sprint without its dates is not a sprint.
	 */
	private void copyBoard(Project source, Project copy, Trace trace) {
		Query query = Query.query(Criteria.where("projectIds").is(source.getId()));
		for (AgileBoard board : mongo.find(query, AgileBoard.class)) {
			if (board.getProjectIds() == null || board.getProjectIds().size() != 1) {
				continue;
			}
			AgileBoard created = AgileBoard.builder()
					.id(UUID.randomUUID().toString())
					.name(copy.getName())
					.type(board.getType())
					.projectIds(new ArrayList<>(List.of(copy.getId())))
					.columns(new ArrayList<>(nonNull(board.getColumns())))
					.columnsCustomized(board.hasCustomColumns() ? Boolean.TRUE : null)
					.ownerId(copy.getLeadId())
					.build();
			mongo.insert(created);
			trace.boardIds.add(created.getId());
			// One board per copy. A project with several of its own is unusual, and copying
			// them all would produce a workspace nobody asked for.
			return;
		}
	}

	/**
	 * The project's time settings, if it has any.
	 *
	 * <p>The document is named, never the module: {@code ProjectTimeSettings} is the storage
	 * contract of a collection, and reading it here does not make project templates depend on
	 * whether time tracking is switched on.
	 */
	private void copyTimeSettings(String sourceId, String copyId, Trace trace) {
		ProjectTimeSettings stored = mongo.findOne(
				Query.query(Criteria.where("projectId").is(sourceId)), ProjectTimeSettings.class);
		if (stored == null) {
			return;
		}
		// Field by field, like everything else here. `toBuilder()` would carry whatever the
		// document grows next, and the coverage test only watches Project and Issue — so a new
		// field would travel silently. It would also carry the original's `updatedBy` and its
		// lock date into a project where nothing has been approved or invoiced yet.
		ProjectTimeSettings copy = ProjectTimeSettings.builder()
				.id(UUID.randomUUID().toString())
				.projectId(copyId)
				.budgetMinutes(stored.getBudgetMinutes())
				.defaultBillable(stored.getDefaultBillable())
				.approvalRequired(stored.getApprovalRequired())
				.approvalPeriod(stored.getApprovalPeriod())
				.alertThresholds(stored.getAlertThresholds())
				.build();
		mongo.insert(copy);
		trace.timeSettingsIds.add(copy.getId());
	}

	// --- files ----------------------------------------------------------------

	/**
	 * Duplicates one issue's files onto its copy: a new attachment id, a new object key and a
	 * store-side copy of the bytes.
	 *
	 * <p>Store-side, like the issue clone: reading every megabyte into this application and
	 * writing it back would move the data twice for a copy the object store makes by itself.
	 *
	 * <p>A file that will not copy is left out and logged rather than raised. A project that fails
	 * to exist because one picture could not be duplicated is a worse answer than a project with
	 * one picture missing — and unlike a broken reference, a missing file is visible.
	 */
	private List<Issue.Attachment> copyAttachments(Issue original, Project copy, Trace trace) {
		List<Issue.Attachment> copies = new ArrayList<>();
		if (original.getAttachments() == null || original.getAttachments().isEmpty()) {
			return copies;
		}
		Instant now = Instant.now();
		for (Issue.Attachment source : original.getAttachments()) {
			String id = UUID.randomUUID().toString();
			String objectKey = StorageService.newObjectKey();
			if (!storage.copyObject(source.getObjectKey(), objectKey)) {
				log.warn("Copying project {}: attachment {} could not be copied, leaving it out",
						copy.getKey(), source.getId());
				continue;
			}
			trace.objectKeys.add(objectKey);
			if (ImagePreviewService.isPreviewable(source.getContentType())) {
				storage.copyObject(ImagePreviewService.attachmentThumbnailKey(source.getId()),
						ImagePreviewService.attachmentThumbnailKey(id));
				trace.thumbnailIds.add(id);
			}
			copies.add(Issue.Attachment.builder()
					.id(id)
					.fileName(source.getFileName())
					.contentType(source.getContentType())
					.size(source.getSize())
					.objectKey(objectKey)
					.uploaderId(source.getUploaderId())
					.uploadedAt(now)
					.blurHash(source.getBlurHash())
					.build());
		}
		return copies;
	}

	/**
	 * Refuses a copy that would duplicate more than the budget allows, before anything is copied.
	 *
	 * <p>Loud rather than quietly truncating: the caller has an obvious way around it, which is to
	 * copy the project without its attachments. A copy that silently arrived with the first fifty
	 * of two hundred files is a half-answer nobody notices until the missing file is the one that
	 * was needed.
	 */
	private static void assertWithinFileBudget(List<Issue> plan) {
		int files = 0;
		long bytes = 0;
		for (Issue issue : plan) {
			for (Issue.Attachment attachment : nonNull(issue.getAttachments())) {
				files++;
				bytes += attachment.getSize();
			}
		}
		if (files > MAX_COPIED_FILES) {
			throw ApiException.badRequest("error.project.copyTooManyAttachments",
					MAX_COPIED_FILES, files);
		}
		if (bytes > MAX_COPIED_BYTES) {
			throw ApiException.badRequest("error.project.copyAttachmentsTooLarge",
					MAX_COPIED_BYTES / (1024 * 1024));
		}
	}

	// --- taking it back -------------------------------------------------------

	/** Everything one call has written, in case it has to be unwritten. */
	private static final class Trace {
		private String projectId;
		private final List<String> issueIds = new ArrayList<>();
		private final List<String> linkIds = new ArrayList<>();
		private final List<String> boardIds = new ArrayList<>();
		private final List<String> timeSettingsIds = new ArrayList<>();
		private final List<String> objectKeys = new ArrayList<>();
		private final List<String> thumbnailIds = new ArrayList<>();
	}

	/**
	 * Removes what a failed copy managed to write, in the reverse order it was written.
	 *
	 * <p>Best effort, and deliberately so: the caller is already on the way out with the real
	 * failure, and replacing it with a cleanup error would report the wrong thing. What cannot be
	 * removed is logged loudly enough to be found.
	 */
	private void rollback(Trace trace) {
		try {
			if (!trace.linkIds.isEmpty()) {
				mongo.remove(Query.query(Criteria.where("_id").in(trace.linkIds)), IssueLink.class);
			}
			if (!trace.issueIds.isEmpty()) {
				mongo.remove(Query.query(Criteria.where("_id").in(trace.issueIds)), Issue.class);
			}
			if (!trace.boardIds.isEmpty()) {
				mongo.remove(Query.query(Criteria.where("_id").in(trace.boardIds)), AgileBoard.class);
			}
			if (!trace.timeSettingsIds.isEmpty()) {
				mongo.remove(Query.query(Criteria.where("_id").in(trace.timeSettingsIds)),
						ProjectTimeSettings.class);
			}
			if (trace.projectId != null) {
				mongo.remove(Query.query(Criteria.where("_id").is(trace.projectId)), Project.class);
			}
		}
		catch (RuntimeException cleanupFailed) {
			log.error("Rolling back a failed project copy left rows behind: project={} issues={}",
					trace.projectId, trace.issueIds.size(), cleanupFailed);
		}
		// The bytes exist before the rows that name them, so a failure that leaves them behind
		// leaves objects nothing will ever name again — and nothing reaps them, because the
		// orphan sweep only knows the media/ prefix.
		trace.objectKeys.forEach(storage::delete);
		trace.thumbnailIds.forEach(id ->
				storage.delete(ImagePreviewService.attachmentThumbnailKey(id)));
	}

	private static <T> List<T> nonNull(List<T> values) {
		return values == null ? List.of() : values;
	}
}
