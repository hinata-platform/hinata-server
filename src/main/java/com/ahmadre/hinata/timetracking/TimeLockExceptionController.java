package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The way back out of the lock date.
 *
 * <p>Before this, the only way to correct one day inside a closed period was to
 * clear the lock date altogether — which reopens <em>everything</em> after it, for
 * everyone, to fix one entry. That is not a proportionate remedy, and a freeze
 * with no proportionate remedy collides with Art. 16 DSGVO: working time is
 * personal data, and inaccurate personal data has to be correctable without undue
 * delay.
 *
 * <p>So an exception opens exactly its own span, carries a reason, names its
 * author, and is recorded — the same shape as reopening an approved period, which
 * is the other freeze this stage builds a way back from. One vocabulary for both
 * was the point.
 *
 * <p>Administrators only, and not leads. The lock date is the instance's archive;
 * a project lead may close their own project's books earlier
 * ({@code ProjectTimeSettings.lockBefore}) but must not be able to open what an
 * administrator has closed.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/lock-exceptions")
@RequiredArgsConstructor
public class TimeLockExceptionController {

	private final SettingsService settings;
	private final TimeTrackingSettings policy;
	private final CurrentUser currentUser;
	private final AuditService audit;
	private final Clock clock;

	public record LockExceptionResponse(String id, LocalDate from, LocalDate to, String note,
			String by, Instant at) {

		static LockExceptionResponse of(ServerSettings.TimeTracking.LockException exception) {
			return new LockExceptionResponse(exception.getId(), exception.getFrom(),
					exception.getTo(), exception.getNote(), exception.getBy(), exception.getAt());
		}
	}

	/**
	 * A span to reopen, and why.
	 *
	 * <p>{@code note} is mandatory. An exception without a stated purpose is
	 * indistinguishable from somebody quietly editing a closed month, which is the
	 * exact thing the lock date exists to prevent.
	 */
	@Data
	public static final class LockExceptionRequest {
		@NotNull(message = "error.time.lockExceptionInvalid")
		private LocalDate from;
		@NotNull(message = "error.time.lockExceptionInvalid")
		private LocalDate to;
		@NotBlank(message = "error.time.lockExceptionNoteRequired")
		@Size(max = TimePolicy.LOCK_NOTE_MAX, message = "error.time.noteTooLong")
		private String note;
	}

	/**
	 * The exceptions as they now stand, so a save does not need a second request.
	 *
	 * <p>There is no GET. An exception is read in two places and both already have
	 * it: members through {@code GET /api/v1/time/policy}, which publishes every
	 * rule they are held to, and the admin screen through the stored settings block
	 * it is editing. A third route would be a third thing to keep in step.
	 */
	private List<LockExceptionResponse> current() {
		return policy.lockExceptions().stream().map(LockExceptionResponse::of).toList();
	}

	@PostMapping
	public List<LockExceptionResponse> add(@RequestBody @Valid LockExceptionRequest request) {
		User user = requireAdmin();
		LocalDate from = request.getFrom();
		LocalDate to = request.getTo();
		if (to.isBefore(from)) {
			throw ApiException.badRequest("error.time.lockExceptionInvalid");
		}
		if (from.plusDays(TimePolicy.PERIOD_MAX_DAYS - 1L).isBefore(to)) {
			throw ApiException.badRequest("error.time.periodTooLong");
		}
		ServerSettings stored = settings.get();
		ServerSettings.TimeTracking block = blockOf(stored);
		List<ServerSettings.TimeTracking.LockException> exceptions =
				new ArrayList<>(block.getLockExceptions() == null ? List.of()
						: block.getLockExceptions());
		if (exceptions.size() >= TimePolicy.LOCK_EXCEPTIONS_MAX) {
			throw ApiException.badRequest("error.time.lockExceptionsTooMany");
		}
		ServerSettings.TimeTracking.LockException exception =
				new ServerSettings.TimeTracking.LockException();
		// The server mints the id, the author and the timestamp. A client that
		// could name any of those is not an audit trail.
		exception.setId(UUID.randomUUID().toString());
		exception.setFrom(from);
		exception.setTo(to);
		exception.setNote(request.getNote().trim());
		exception.setBy(user.getId());
		exception.setAt(clock.instant());
		exceptions.add(exception);
		block.setLockExceptions(exceptions);
		settings.save(stored);
		audit.event(AuditAction.TIME_LOCK_EXCEPTION_ADDED).actor(user)
				.target(exception.getId(), from + " – " + to)
				.meta("from", String.valueOf(from))
				.meta("to", String.valueOf(to))
				.meta("note", exception.getNote())
				.log();
		return current();
	}

	@DeleteMapping("/{id}")
	public List<LockExceptionResponse> remove(@PathVariable String id) {
		User user = requireAdmin();
		ServerSettings stored = settings.get();
		ServerSettings.TimeTracking block = blockOf(stored);
		List<ServerSettings.TimeTracking.LockException> exceptions =
				new ArrayList<>(block.getLockExceptions() == null ? List.of()
						: block.getLockExceptions());
		ServerSettings.TimeTracking.LockException removed = exceptions.stream()
				.filter(exception -> id.equals(exception.getId()))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("timeLockException"));
		exceptions.remove(removed);
		block.setLockExceptions(exceptions);
		settings.save(stored);
		audit.event(AuditAction.TIME_LOCK_EXCEPTION_REMOVED).actor(user)
				.target(removed.getId(), removed.getFrom() + " – " + removed.getTo())
				.meta("from", String.valueOf(removed.getFrom()))
				.meta("to", String.valueOf(removed.getTo()))
				.meta("note", removed.getNote())
				.log();
		return current();
	}

	/**
	 * An administrator, or 403.
	 *
	 * <p>Checked here rather than inherited: these routes sit under
	 * {@code /api/v1/time} so that they are gated with the module, which means
	 * {@code SecurityConfig}'s blanket rule for {@code /api/v1/admin/**} does not
	 * reach them. Moving them under the admin prefix instead would put them outside
	 * the module gate, and a route that survives the module being switched off is
	 * worse than one line of authorisation.
	 */
	private User requireAdmin() {
		User user = currentUser.require();
		if (!user.isAdmin()) {
			throw ApiException.forbidden("error.time.lockExceptionsAdminOnly");
		}
		return user;
	}

	/**
	 * The stored block, created if the instance has never had one.
	 *
	 * <p>Creating it here writes no policy: every field stays null, which is what
	 * "the environment decides" means everywhere else in the block. An exception is
	 * the one thing in it that has no environment form, so it is also the one thing
	 * that can need the block brought into existence.
	 */
	private static ServerSettings.TimeTracking blockOf(ServerSettings stored) {
		ServerSettings.TimeTracking block = stored.getTimeTracking();
		if (block == null) {
			block = new ServerSettings.TimeTracking();
			stored.setTimeTracking(block);
		}
		return block;
	}
}
