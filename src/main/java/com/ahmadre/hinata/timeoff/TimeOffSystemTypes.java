package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The three types an instance starts with, created the first time the module is switched on.
 *
 * <p>An empty catalogue is a bad first impression and a worse first hour: somebody turns absence
 * management on, opens it, and has to invent "vacation" before anybody can ask for a day off. So
 * vacation, sickness and "other" are there, configured the way German law describes the common
 * case — twenty days of vacation subject to approval, carried to 31 March; sickness unlimited and
 * never subject to approval; other unlimited and unpaid.
 *
 * <p>Twenty days rather than thirty: § 3 Abs. 1 BUrlG grants 24 working days on a six-day week,
 * which is twenty on a five-day week. A default should be the floor the law sets, not a number
 * that quietly promises more than an employer agreed to — an operator who grants thirty changes
 * one field and knows they did.
 *
 * <p>They are created <b>without a name</b>. A name is free text in whatever language an operator
 * writes, and shipping "Vacation" would leave a German instance reading English until somebody
 * renamed three rows. A system type with no name of its own is rendered from the built-in label
 * for its {@code systemKey} — translated like everything else — and the moment an operator types
 * one, theirs wins and keeps winning.
 *
 * <p>Created on startup and whenever the settings change, both guarded by the flag and both
 * idempotent. Never on a read: a GET that writes is a GET that behaves differently under load,
 * and the unique index on the key would make two instances race for it. That index is also the
 * answer when they race here — the loser catches a duplicate and carries on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeOffSystemTypes implements ApplicationRunner {

	/** The default vacation allowance: the statutory floor on a five-day week, in thousandths. */
	static final int DEFAULT_VACATION_MILLI_DAYS = 20 * TimeOffType.DAY;

	private final TimeOffTypeRepository types;
	private final TimeOffSettings settings;

	@Override
	public void run(ApplicationArguments args) {
		ensure();
	}

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		// So switching the module on brings its catalogue with it, before anybody opens the screen.
		ensure();
	}

	/** Creates whichever of the three is missing. Does nothing at all while the module is off. */
	public void ensure() {
		if (!settings.enabled()) {
			return;
		}
		for (TimeOffType candidate : defaults()) {
			if (types.findBySystemKey(candidate.getSystemKey()).isPresent()) {
				continue;
			}
			try {
				types.save(candidate);
				log.info("[timeoff] created the system absence type {}", candidate.getKey());
			}
			catch (DuplicateKeyException raced) {
				// Another instance got there first, or an operator already has a type under this
				// key. Either way the catalogue now holds one, which is all this wanted.
				log.debug("[timeoff] system absence type {} already exists", candidate.getKey());
			}
		}
	}

	private static List<TimeOffType> defaults() {
		return List.of(
				TimeOffType.builder()
						.key(TimeOffType.SYSTEM_VACATION)
						.systemKey(TimeOffType.SYSTEM_VACATION)
						.icon("palmtree")
						.hue(35)
						.kind(TimeOffType.Kind.VACATION)
						.paid(true)
						.countsAgainstBalance(true)
						.unlimited(false)
						.approvalRequired(true)
						.approverRule(TimeOffType.ApproverRule.TEAM_LEAD)
						.halfDaysAllowed(true)
						.fractionAllowed(false)
						.accrual(TimeOffType.Accrual.ANNUAL)
						.allowanceMilliDays(DEFAULT_VACATION_MILLI_DAYS)
						.waitingPeriodMonths(6)
						.prorateOnJoin(true)
						.prorateOnLeave(true)
						.carryover(TimeOffType.Carryover.UNLIMITED)
						.visibility(TimeOffType.Visibility.SELF_ONLY)
						.active(true)
						.build(),
				TimeOffType.builder()
						.key(TimeOffType.SYSTEM_SICK)
						.systemKey(TimeOffType.SYSTEM_SICK)
						.icon("thermometer")
						.hue(0)
						.kind(TimeOffType.Kind.SICK)
						.paid(true)
						.countsAgainstBalance(false)
						.unlimited(true)
						// Not "false because we chose false": a sick type cannot be subject to
						// approval at all (R11, § 5 EFZG). TimeOffType.requiresApproval() enforces
						// it whatever is stored; this only avoids storing a contradiction.
						.approvalRequired(false)
						.halfDaysAllowed(true)
						.fractionAllowed(false)
						.accrual(TimeOffType.Accrual.NONE)
						.carryover(TimeOffType.Carryover.NONE)
						.visibility(TimeOffType.Visibility.SELF_ONLY)
						.active(true)
						.build(),
				TimeOffType.builder()
						.key(TimeOffType.SYSTEM_OTHER)
						.systemKey(TimeOffType.SYSTEM_OTHER)
						.icon(TimeOffIcons.DEFAULT)
						.hue(220)
						.kind(TimeOffType.Kind.OTHER)
						.paid(false)
						.countsAgainstBalance(false)
						.unlimited(true)
						.approvalRequired(false)
						.approverRule(TimeOffType.ApproverRule.TEAM_LEAD)
						.halfDaysAllowed(true)
						.fractionAllowed(false)
						.accrual(TimeOffType.Accrual.NONE)
						.carryover(TimeOffType.Carryover.NONE)
						.visibility(TimeOffType.Visibility.SELF_ONLY)
						.active(true)
						.build());
	}
}
