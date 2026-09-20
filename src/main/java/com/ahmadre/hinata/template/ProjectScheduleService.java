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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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

	private final ProjectService projects;
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

		/** Days between the two, for a client that wants to say "+7" without parsing dates. */
		public long shiftDays() {
			return from == null || to == null ? 0 : from.toEpochDay() - to.toEpochDay();
		}
	}

	/**
	 * What moving the event date to {@code eventDate} would do.
	 *
	 * @param limit how many moves to name; the rest are only counted. Null takes
	 *              {@link #PREVIEW_LIMIT}, which is what the sheet shows before "and n more".
	 */
	public Preview preview(String projectId, LocalDate eventDate, Integer limit, User user) {
		Project project = projects.get(projectId);
		projects.assertLeadOrAdmin(project, user);
		return previewOf(project, eventDate, limit);
	}

	/** The preview for a project already loaded and already permitted. */
	public Preview previewOf(Project project, LocalDate eventDate, Integer limit) {
		int named = limit == null || limit <= 0 ? PREVIEW_LIMIT : limit;
		WorkdayCalendar calendar = calendars.of(project);
		List<Move> moves = new ArrayList<>();
		int moved = 0;
		int unchanged = 0;
		int pending = 0;
		int manual = 0;
		for (Issue issue : withOffsets(project.getId())) {
			boolean touched = false;
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
				touched = true;
				if (moves.size() < named) {
					moves.add(new Move(issue.getId(), issue.getReadableId(), issue.getTitle(),
							field, before, after));
				}
			}
			if (touched) {
				moved++;
			}
		}
		manual = countManual(project.getId());
		return new Preview(project.getEventDate(), eventDate, shift(project.getEventDate(), eventDate),
				moved, unchanged, pending, manual, moves.size(), moves);
	}

	/**
	 * Sets the event date and writes the deadlines that follow from it.
	 *
	 * <p>In batches rather than document by document, and with the issues' own {@code updatedAt}
	 * left to the write: this is a hundred small writes that belong to one decision, and a
	 * hundred round trips would make an ordinary reschedule feel like an import.
	 */
	public Result apply(String projectId, LocalDate eventDate, User user) {
		Project project = projects.get(projectId);
		projects.assertLeadOrAdmin(project, user);

		LocalDate previous = project.getEventDate();
		Preview preview = previewOf(project, eventDate, Integer.MAX_VALUE);
		project.setEventDate(eventDate);
		projects.save(project);

		int written = 0;
		if (eventDate != null) {
			written = writeDeadlines(project, eventDate);
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

	/** Every issue of the project that carries at least one offset. */
	private List<Issue> withOffsets(String projectId) {
		Query query = Query.query(Criteria.where("projectId").is(projectId)
				.orOperator(Criteria.where("startOffset").ne(null),
						Criteria.where("dueOffset").ne(null)));
		return mongo.find(query, Issue.class);
	}

	/**
	 * Issues whose deadline somebody typed: a date, no offset. Counted so the sheet can say "3
	 * deadlines stay where they are" instead of leaving the difference unexplained.
	 */
	private int countManual(String projectId) {
		Query query = Query.query(Criteria.where("projectId").is(projectId)
				.and("dueDate").ne(null).and("dueOffset").is(null));
		return (int) mongo.count(query, Issue.class);
	}

	/** Writes every resolved date, in batches. Returns how many deadlines were written. */
	private int writeDeadlines(Project project, LocalDate eventDate) {
		WorkdayCalendar calendar = calendars.of(project);
		List<Issue> issues = withOffsets(project.getId());
		int written = 0;
		BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Issue.class);
		int pending = 0;
		for (Issue issue : issues) {
			Update update = new Update();
			boolean touched = false;
			LocalDate start = RelativeDates.resolve(eventDate, issue.getStartOffset(), calendar);
			if (start != null && !start.equals(issue.getStartDate())) {
				update.set("startDate", start);
				touched = true;
			}
			LocalDate due = RelativeDates.resolve(eventDate, issue.getDueOffset(), calendar);
			if (due != null && !due.equals(issue.getDueDate())) {
				update.set("dueDate", due);
				// So the reminder job re-arms for the new day rather than staying silent
				// because it once reminded about the old one.
				update.unset("dueReminderFor");
				touched = true;
			}
			if (!touched) {
				continue;
			}
			bulk.updateOne(Query.query(Criteria.where("_id").is(issue.getId())), update);
			written++;
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
		List<IssueActivity> entries = new ArrayList<>(moves.size());
		for (Move move : moves) {
			entries.add(IssueActivity.builder()
					.issueId(move.issueId())
					.actorId(user == null ? null : user.getId())
					.field(move.field() == Move.Field.START
							? IssueActivity.Field.START_DATE : IssueActivity.Field.DUE_DATE)
					.fromValue(text(move.from()))
					.toValue(text(move.to()))
					.build());
		}
		if (!entries.isEmpty()) {
			activities.saveAll(entries);
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
	 * What a move would do. {@code moved} counts issues, {@code named} counts the rows below —
	 * the client shows those and says "and n more" about the rest.
	 */
	public record Preview(LocalDate eventDate, LocalDate newEventDate, Long shiftDays,
			int moved, int unchanged, int pending, int manual, int named, List<Move> moves) {
	}

	/** What a move did. */
	public record Result(Project project, int deadlinesMoved, int leftAlone) {
	}
}
