package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.mcp.McpErrors;
import com.ahmadre.hinata.mcp.ScopeGuard;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP tools for the caller's own absences (HIN-97): what is left of their leave, and asking for
 * some.
 *
 * <p>They live in this package rather than in {@code mcp/} because nothing outside absence
 * management may reach into it — the module has to be switchable off, and an adapter elsewhere
 * would be a second way in that the switch does not see. For the same reason each tool asks
 * {@link TimeOffSettings#enabled()} itself: MCP does not pass the {@code AbsenceManagementGate}.
 *
 * <p>What is deliberately missing: no tool reads anybody else's balance, no tool decides a
 * request — approving leave stays something a person does in the app (Art. 22 DSGVO) — and sick
 * leave is neither reported nor read here. A sick type is left out of the balance and refused as a
 * request (the service already refuses it), because an agent has no business carrying health data
 * to a model provider (R11, Art. 9 DSGVO).
 */
@Service
@RequiredArgsConstructor
public class TimeOffTools {

	private final TimeOffBalanceService balances;
	private final TimeOffTypeService types;
	private final TimeOffRequestService requests;
	private final TimeOffSettings settings;
	private final CurrentUser currentUser;
	private final ScopeGuard scopeGuard;
	private final McpErrors errors;
	private final UserWords words;
	private final Clock clock;

	/**
	 * One type's standing in days (a half day is 0.5). {@code expiringDays} lapse on
	 * {@code expiringOn} unless taken before; {@code unlimited} types have no quota to run out of.
	 */
	public record BalanceView(String typeId, String type, String kind, int year, double entitledDays,
			double takenDays, double plannedDays, double remainingDays, boolean unlimited,
			double expiringDays, LocalDate expiringOn) {
	}

	/** A request as filed: its state says whether it waits for somebody or was decided at once. */
	public record RequestView(String id, String typeId, LocalDate from, LocalDate to, double days,
			int workingDays, String status) {
	}

	@McpTool(name = "my_time_off_balance", title = "My time-off balance",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "The caller's own leave per absence type for a year (default: this year): "
					+ "entitled, taken, planned and remaining days, and what lapses when. The typeId "
					+ "is what request_time_off asks for. Sick leave is not listed.")
	public List<BalanceView> my_time_off_balance(
			@McpToolParam(required = false, description = "Leave year, e.g. 2026; this year when omitted") Integer year) {
		User me = caller(Scopes.WORKLOG_READ);
		errors.requireEnabled(settings.enabled(), me);
		return balanceOf(me, year);
	}

	@McpTool(name = "request_time_off", title = "Request time off",
			annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false),
			description = "Ask for time off for the caller: an absence type (typeId from "
					+ "my_time_off_balance), a first and last day, optionally a part of the first or "
					+ "last day in thousandths (500 = half a day), a note and a colleague who stands "
					+ "in (user id). It goes to whoever decides it, exactly as from the app; a type "
					+ "nobody has to approve is approved at once. Sick leave cannot be requested here. "
					+ "Returns the filed request.")
	public RequestView request_time_off(
			@McpToolParam(required = true, description = "Absence type id, from my_time_off_balance") String typeId,
			@McpToolParam(required = true, description = "First day (yyyy-MM-dd)") LocalDate from,
			@McpToolParam(required = false, description = "Last day (yyyy-MM-dd); the first day when omitted") LocalDate to,
			@McpToolParam(required = false, description = "Part of the first day, 1-1000 thousandths; a whole day when omitted") Integer firstDayMilliDays,
			@McpToolParam(required = false, description = "Part of the last day, 1-1000 thousandths; a whole day when omitted") Integer lastDayMilliDays,
			@McpToolParam(required = false, description = "Note for whoever decides (at most 1000 characters)") String note,
			@McpToolParam(required = false, description = "User id of a colleague who stands in") String substituteId) {
		User me = caller(Scopes.WORKLOG_WRITE);
		errors.requireEnabled(settings.enabled(), me);
		errors.requireRange(firstDayMilliDays, 1, TimeOffType.DAY, "firstDayMilliDays", me);
		errors.requireRange(lastDayMilliDays, 1, TimeOffType.DAY, "lastDayMilliDays", me);
		errors.requireLength(note, TimeOffRequest.NOTE_MAX, "note", me);
		TimeOffRequest filed = errors.readable(me, () -> requests.submit(me,
				new TimeOffRequestService.Draft(typeId, from, to, firstDayMilliDays, lastDayMilliDays,
						note, substituteId)));
		return new RequestView(filed.getId(), filed.getTypeId(), filed.getFrom(), filed.getTo(),
				days(filed.getMilliDays()), filed.getWorkingDays() == null ? 0 : filed.getWorkingDays(),
				filed.getStatus().name());
	}

	@McpResource(name = "time-off-balance", uri = "hinata://time-off/balance",
			description = "The caller's own leave balance for this year, per absence type, as markdown.",
			mimeType = "text/markdown")
	public String balanceResource() {
		User me = caller(Scopes.WORKLOG_READ);
		errors.requireEnabled(settings.enabled(), me);
		List<BalanceView> rows = balanceOf(me, null);
		StringBuilder md = new StringBuilder("# Time off ")
				.append(rows.isEmpty() ? LocalDate.now(clock).getYear() : rows.getFirst().year()).append("\n\n");
		if (rows.isEmpty()) {
			return md.append("No absence types are offered.\n").toString();
		}
		for (BalanceView row : rows) {
			md.append("- ").append(row.type()).append(": ");
			if (row.unlimited()) {
				md.append("no quota, ").append(row.takenDays()).append(" taken");
			}
			else {
				md.append(row.remainingDays()).append(" of ").append(row.entitledDays()).append(" left");
			}
			if (row.plannedDays() > 0) {
				md.append(", ").append(row.plannedDays()).append(" planned");
			}
			if (row.expiringDays() > 0 && row.expiringOn() != null) {
				md.append(", ").append(row.expiringDays()).append(" lapse on ").append(row.expiringOn());
			}
			md.append('\n');
		}
		return md.toString();
	}

	private List<BalanceView> balanceOf(User me, Integer year) {
		int leaveYear = year != null ? year : LocalDate.now(clock).getYear();
		return errors.readable(me, () -> {
			Map<String, TimeOffType> byId = types.list(me, true).stream()
					.collect(Collectors.toMap(TimeOffType::getId, Function.identity(), (first, second) -> first));
			return balances.balances(me, me.getId(), leaveYear).stream()
					.filter(balance -> {
						TimeOffType type = byId.get(balance.typeId());
						return type != null && type.getKind() != TimeOffType.Kind.SICK;
					})
					.map(balance -> view(balance, byId.get(balance.typeId()), me))
					.toList();
		});
	}

	private BalanceView view(TimeOffBalanceService.Balance balance, TimeOffType type, User me) {
		return new BalanceView(balance.typeId(), nameOf(type, me),
				type.getKind() == null ? null : type.getKind().name(), balance.year(),
				days(balance.entitledMilliDays()), days(balance.takenMilliDays()),
				days(balance.plannedMilliDays()), days(balance.remainingMilliDays()), balance.unlimited(),
				days(balance.expiringMilliDays()), balance.expiringOn());
	}

	/** What the type is called for this reader: an operator's name, or the built-in label. */
	private String nameOf(TimeOffType type, User me) {
		if (type.getName() != null && !type.getName().isBlank()) {
			return type.getName();
		}
		return words.of(me, "timeOff.type." + (type.getSystemKey() == null ? "other" : type.getSystemKey()));
	}

	private static double days(Integer milliDays) {
		return milliDays == null ? 0 : milliDays / (double) TimeOffType.DAY;
	}

	/**
	 * The caller, once the token has shown [scope]. The refusal goes through {@link McpErrors} like
	 * every other one here, so an agent reads which scope is missing rather than a message key.
	 */
	private User caller(String scope) {
		User me = currentUser.require();
		errors.readable(me, () -> scopeGuard.require(scope));
		return me;
	}
}
