package com.ahmadre.hinata.template;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueActivity;
import com.ahmadre.hinata.issue.IssueActivityRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.schema.JsonSchemaObject;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Moving a project's event date, and saying beforehand what that would do.
 *
 * <p>The preview is not a nicety. A date field on the settings page that silently rewrote twelve
 * deadlines would be a screen that does something other than what it looks like, on a page
 * somebody opened to change a colour. So the two halves are two requests: one that computes and
 * changes nothing, one that writes what was just shown.
 *
 * <p>What moves is exactly the issues that carry an offset. A deadline somebody typed by hand has
 * no offset — the write path clears it — so it is counted, named and left alone. That is the
 * promise the whole feature rests on: a deliberate date is never overwritten by a rule.
 */
@Service
@RequiredArgsConstructor
public class ProjectScheduleService {

	/** How many issues are rewritten per round trip to the database. */
	private static final int BATCH = 200;

	/** How many moved deadlines a preview names before it only counts them. */
	public static final int PREVIEW_LIMIT = 20;

	/**
	 * The most a client may ask to have named, whatever it sends. The rows are only there to
	 * recognise the shape of a move; a client asking for all of them would make a read route's
	 * response grow with the project.
	 */
	public static final int PREVIEW_LIMIT_MAX = 200;

	private final ProjectService projects;
	private final ProjectTemplateSettings settings;
	private final MongoTemplate mongo;
	private final IssueActivityRepository activities;
	private final HolidayCalendars calendars;
	private final AuditService audit;

	/** One deadline that would move, or did. */
	public record Move(String issueId, String readableId, String title, Field field,
			LocalDate from, LocalDate to) {

		/** Which of the two planning dates moved. */
		public enum Field {
			START, DUE
		}

	}

	/**
	 * What moving the event date to {@code eventDate} would do.
	 *
	 * @param limit how many moves to name; the rest are only counted. Null takes
	 *              {@link #PREVIEW_LIMIT}, which is what the sheet shows before "and n more".
	 */
	public Preview preview(String projectId, LocalDate eventDate, Integer limit, User user) {
		requireModule();
		Project project = projects.get(projectId);
		projects.assertLeadOrAdmin(project, user);
		return previewOf(project, eventDate, limit);
	}

	/**
	 * Refuses when the module is off.
	 *
	 * <p>The interceptor already answers every HTTP route, so this is the second lock on the same
	 * door — for the callers that reach a service another way, which is the rule this module set
	 * itself and the one a future MCP tool would otherwise land on the wrong side of.
	 */
	private void requireModule() {
		if (!settings.enabled()) {
			throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
					ProjectTemplateGate.DISABLED_KEY);
		}
	}

	/** The preview for a project already loaded and already permitted. */
	private Preview previewOf(Project project, LocalDate eventDate, Integer limit) {
		return previewOf(project, eventDate, namedLimit(limit), withOffsets(project.getId()),
				calendars.of(project));
	}

	/**
	 * How many moves a <em>client</em> may have named. Bounded, because the rows only exist to
	 * recognise the shape of a move and a response that grew with the project would be a read
	 * route anybody could turn into a download.
	 *
	 * <p>Deliberately not applied to what {@code apply} asks for: that call feeds the activity
	 * log, which needs one line per moved deadline. Clamping it there wrote two hundred history
	 * entries for five thousand changed dates and left the rest moving with no trace — the exact
	 * silence {@code recordActivities} exists to prevent.
	 */
	private static int namedLimit(Integer limit) {
		return limit == null || limit <= 0 ? PREVIEW_LIMIT : Math.min(limit, PREVIEW_LIMIT_MAX);
	}

	/**
	 * The preview over a plan and a calendar the caller already holds.
	 *
	 * <p>{@code apply} reads both once and hands them to the preview and to the write, so moving
	 * a date is one pass over the project rather than three.
	 */
	private Preview previewOf(Project project, LocalDate eventDate, int named,
			List<Issue> plan, WorkdayCalendar calendar) {
		List<Move> moves = new ArrayList<>();
		int moved = 0;
		int unchanged = 0;
		int pending = 0;
		for (Issue issue : plan) {
			for (Move.Field field : Move.Field.values()) {
				RelativeDate offset = offsetOf(issue, field);
				if (offset == null) {
					continue;
				}
				LocalDate before = dateOf(issue, field);
				LocalDate after = RelativeDates.resolve(eventDate, offset, calendar);
				if (after == null) {
					// The event date is being cleared. The deadline stays where it is and the
					// rule stays with the issue, so this is a "nothing happens" rather than a
					// move — but it is worth counting, because the sheet says so out loud.
					pending++;
					continue;
				}
				if (after.equals(before)) {
					unchanged++;
					continue;
				}
				// Counted per deadline, not per issue: an issue whose start and due both move
				// contributes two rows below, and a headline that counted issues over a list of
				// fields would not add up.
				moved++;
				if (moves.size() < named) {
					moves.add(new Move(issue.getId(), issue.getReadableId(), issue.getTitle(),
							field, before, after));
				}
			}
		}
		return new Preview(project.getEventDate(), eventDate, shift(project.getEventDate(), eventDate),
				moved, unchanged, pending, countManual(project.getId()), moves);
	}

	/**
	 * Sets the event date and writes the deadlines that follow from it.
	 *
	 * <p>In batches rather than document by document, and with the issues' own {@code updatedAt}
	 * left to the write: this is a hundred small writes that belong to one decision, and a
	 * hundred round trips would make an ordinary reschedule feel like an import.
	 */
	public Result apply(String projectId, LocalDate eventDate, User user) {
		requireModule();
		Project project = projects.get(projectId);
		projects.assertLeadOrAdmin(project, user);

		LocalDate previous = project.getEventDate();
		// Read once and hand both down. Asking again for the write would walk the project a
		// second time and fetch the same holiday years over again, for one decision.
		WorkdayCalendar calendar = calendars.of(project);
		List<Issue> plan = withOffsets(project.getId());
		// Every move, not a page of them: each one becomes a line in its issue's own history.
		Preview preview = previewOf(project, eventDate, Integer.MAX_VALUE, plan, calendar);
		project.setEventDate(eventDate);
		projects.save(project);

		int written = 0;
		if (eventDate != null) {
			written = writeDeadlines(plan, eventDate, calendar);
		}
		recordActivities(preview.moves(), user);
		audit.event(AuditAction.PROJECT_SCHEDULE_SHIFTED).actor(user)
				.meta("project", project.getKey())
				.meta("eventBefore", text(previous))
				.meta("eventAfter", text(eventDate))
				.meta("deadlinesMoved", String.valueOf(written))
				.meta("leftAlone", String.valueOf(preview.manual()))
				.log();
		return new Result(project, written, preview.manual());
	}

	/**
	 * One offset against a date: the project's own, or one the caller is proposing.
	 *
	 * <p>A read, so seeing the project is enough — this says nothing a member cannot already see
	 * by opening an issue, and refusing it to everyone but the lead would only mean the form
	 * shows nothing while somebody fills it in.
	 */
	public LocalDate resolve(String projectId, LocalDate eventDate, RelativeDate offset,
			User user) {
		requireModule();
		// The same bound preview and apply hold to. Without it a date like +999999999-12-31
		// reaches plusDays and throws out of the arithmetic: a 500 and a stack trace per
		// request, on a route any member may call in a loop.
		LocalDate proposed = checked(eventDate);
		Project project = projects.get(projectId);
		projects.assertMember(project, user);
		if (offset == null) {
			return null;
		}
		if (!offset.withinLimits()) {
			throw ApiException.badRequest("error.issue.offsetOutOfRange",
					RelativeDate.MAX_DAYS, RelativeDate.MAX_WEEKS);
		}
		LocalDate anchor = proposed != null ? proposed : project.getEventDate();
		return RelativeDates.resolve(anchor, offset, calendars.of(project));
	}

	/**
	 * Every issue of the project that carries at least one offset, earliest deadline first.
	 *
	 * <p>Sorted because the preview names only the first few and the sheet presents them as the
	 * front of the timeline; an arbitrary six of forty described as ordered is worse than an
	 * unordered sample. Archived issues are left out for the reason the copy leaves them out:
	 * they are the project's history, and nobody is waiting on their dates.
	 */
	private List<Issue> withOffsets(String projectId) {
		Query query = Query.query(Criteria.where("projectId").is(projectId)
				.and("archived").ne(true)
				.orOperator(Criteria.where("startOffset").ne(null),
						Criteria.where("dueOffset").ne(null)))
				.with(Sort.by("dueDate", "startDate"));
		return mongo.find(query, Issue.class);
	}

	/**
	 * Issues whose deadline somebody typed: a date, no offset. Counted so the sheet can say "3
	 * deadlines stay where they are" instead of leaving the difference unexplained.
	 */
	private int countManual(String projectId) {
		// A type predicate rather than {@code $ne: null}: the negation forces every candidate to
		// be fetched to be evaluated, while "is a date" is a bound the index can answer on its
		// own. The equality on dueOffset comes first for the same reason.
		Query query = Query.query(Criteria.where("projectId").is(projectId)
				.and("dueOffset").is(null)
				.and("startOffset").is(null)
				.and("dueDate").type(JsonSchemaObject.Type.dateType()));
		return (int) mongo.count(query, Issue.class);
	}

	/** Writes every resolved date, in batches. Returns how many deadlines were written. */
	private int writeDeadlines(List<Issue> issues, LocalDate eventDate, WorkdayCalendar calendar) {
		int written = 0;
		BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Issue.class);
		int pending = 0;
		for (Issue issue : issues) {
			Update update = new Update();
			boolean touched = false;
			LocalDate start = RelativeDates.resolve(eventDate, issue.getStartOffset(), calendar);
			if (start != null && !start.equals(issue.getStartDate())) {
				update.set("startDate", start);
				written++;
				touched = true;
			}
			LocalDate due = RelativeDates.resolve(eventDate, issue.getDueOffset(), calendar);
			if (due != null && !due.equals(issue.getDueDate())) {
				update.set("dueDate", due);
				// So the reminder job re-arms for the new day rather than staying silent
				// because it once reminded about the old one.
				update.unset("dueReminderFor");
				written++;
				touched = true;
			}
			if (!touched) {
				continue;
			}
			bulk.updateOne(Query.query(Criteria.where("_id").is(issue.getId())), update);
			if (++pending >= BATCH) {
				bulk.execute();
				bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Issue.class);
				pending = 0;
			}
		}
		if (pending > 0) {
			bulk.execute();
		}
		return written;
	}

	/**
	 * One activity line per moved deadline, so an issue's own history says why its date changed.
	 *
	 * <p>Without it the date simply differs from yesterday and nothing anywhere explains it,
	 * which is the complaint every silent bulk edit eventually produces.
	 */
	private void recordActivities(List<Move> moves, User user) {
		// In the same batches the deadlines are written in, so one very large reschedule does
		// not build the whole list in memory before the first insert.
		for (int from = 0; from < moves.size(); from += BATCH) {
			List<IssueActivity> slice = new ArrayList<>(BATCH);
			for (Move move : moves.subList(from, Math.min(from + BATCH, moves.size()))) {
				slice.add(IssueActivity.builder()
						.issueId(move.issueId())
						.actorId(user == null ? null : user.getId())
						.field(move.field() == Move.Field.START
								? IssueActivity.Field.START_DATE : IssueActivity.Field.DUE_DATE)
						.fromValue(text(move.from()))
						.toValue(text(move.to()))
						.build());
			}
			activities.saveAll(slice);
		}
	}

	private static RelativeDate offsetOf(Issue issue, Move.Field field) {
		return field == Move.Field.START ? issue.getStartOffset() : issue.getDueOffset();
	}

	private static LocalDate dateOf(Issue issue, Move.Field field) {
		return field == Move.Field.START ? issue.getStartDate() : issue.getDueDate();
	}

	private static Long shift(LocalDate from, LocalDate to) {
		return from == null || to == null ? null : to.toEpochDay() - from.toEpochDay();
	}

	private static String text(LocalDate date) {
		return date == null ? null : date.toString();
	}

	/** Refuses a date nobody could have meant, long before it reaches an issue. */
	public static LocalDate checked(LocalDate eventDate) {
		if (eventDate == null) {
			return null;
		}
		if (eventDate.getYear() < 1970 || eventDate.getYear() > 2200) {
			throw ApiException.badRequest("error.project.eventDateOutOfRange");
		}
		return eventDate;
	}

	/**
	 * What a move would do. Every count is of <em>deadlines</em>, not of issues: an issue whose
	 * start and due both move counts twice, because the sheet lists one row per date. {@code
	 * moves} holds the first few by name and the client says "and n more" about the rest.
	 */
	public record Preview(LocalDate eventDate, LocalDate newEventDate, Long shiftDays,
			int moved, int unchanged, int pending, int manual, List<Move> moves) {
	}

	/** What a move did. */
	public record Result(Project project, int deadlinesMoved, int leftAlone) {
	}
}
