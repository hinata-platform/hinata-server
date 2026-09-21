package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the keepers do with the yearly run (HIN-119): read what it did, decide the lapses it
 * proposed after a long illness, and settle the leave of somebody who leaves.
 *
 * <p>Every decision here is a person's, with a reason, because each one takes days away from
 * somebody: a lapse after illness is a finding about their health (Art. 22 DSGVO), and a payout
 * ends a claim the law says may not be traded for money while the employment lasts (§ 7 Abs. 4
 * BUrlG). The run and the server only ever propose.
 *
 * <p><b>hinata computes days, never money.</b> The payout is booked in days; what a day is worth is
 * the payroll's to say.
 */
@Service
@RequiredArgsConstructor
public class TimeOffYearDesk {

	/** Longest page of proposals. */
	public static final int PAGE_MAX = 100;

	private final TimeOffAccess access;
	private final TimeOffTypeRepository types;
	private final TimeOffYearRunRepository runs;
	private final TimeOffProposalRepository proposals;
	private final TimeOffEntitlementRepository entitlements;
	private final TimeOffEmploymentRepository employment;
	private final TimeOffBalanceService balances;
	private final TimeOffYearFigures figures;
	private final UserRepository users;
	private final AuditService audit;
	private final MongoTemplate mongo;
	private final Clock clock;

	/**
	 * What the settlement of one type looks like for somebody leaving: the year recomputed with the
	 * leaving date (§ 5 BUrlG), the correction that takes, and what is left to pay out.
	 *
	 * @param accruedMilliDays  what the year is worth with the leaving date
	 * @param bookedMilliDays   what was booked as accrual so far
	 * @param correctionMilliDays the difference, to book as a correction first; negative when the
	 *                          person leaves in the first half of the year and keeps twelfths only
	 * @param remainingMilliDays what is left now, carried days included
	 * @param payoutMilliDays   what would be left after the correction, and so to be paid out
	 */
	public record Settlement(String typeId, int year, TimeOffBalances.Reason reason, int accruedMilliDays,
			int bookedMilliDays, int correctionMilliDays, int remainingMilliDays, int payoutMilliDays) {
	}

	/** The run's last night, if it ever ran. */
	public Optional<TimeOffYearRunRecord> lastRun(User actor) {
		access.requireKeeper(actor);
		return runs.findFirstByOrderByIdDesc();
	}

	// --- proposals after a long illness ---------------------------------------------

	/** The proposals still waiting for a keeper, oldest first. */
	public Page<TimeOffProposal> openProposals(User actor, int page, int size) {
		access.requireKeeper(actor);
		return proposals.findByStatusOrderByCreatedAtAsc(TimeOffProposal.Status.OPEN,
				PageRequest.of(Math.max(0, page), Math.clamp(size, 1, PAGE_MAX)));
	}

	/**
	 * Confirms a proposed lapse: books it, with the keeper's reason, for no more than is still
	 * held — days taken since the proposal was made are not taken twice.
	 */
	public TimeOffProposal confirm(User actor, String proposalId, String reason) {
		access.requireKeeper(actor);
		String text = requireReason(reason);
		TimeOffProposal proposal = claim(actor, proposalId, TimeOffProposal.Status.CONFIRMED, text);
		TimeOffType type = types.findById(proposal.getTypeId())
				.orElseThrow(() -> ApiException.notFound("timeOffType"));
		int year = proposal.getHeldIn() == null ? proposal.getYear() : proposal.getHeldIn();
		int held = TimeOffYearFigures.yearOf(
				figures.sums(type.getId(), List.of(year), List.of(proposal.getUserId())), proposal.getUserId(), year)
				.carriedUnused();
		int lapse = Math.min(held, proposal.milliDays());
		if (lapse > 0) {
			TimeOffLedgerEntry booked = balances.book(TimeOffLedgerEntry.builder()
					.userId(proposal.getUserId()).typeId(type.getId()).year(year)
					.kind(TimeOffLedgerEntry.Kind.EXPIRED).milliDays(-lapse).effectiveOn(LocalDate.now(clock))
					.refId(proposal.getId()).reason(text).actorId(actor.getId()).build());
			proposal.setLedgerId(booked.getId());
			mongo.updateFirst(Query.query(Criteria.where("_id").is(proposal.getId())),
					new Update().set("ledgerId", booked.getId()), TimeOffProposal.class);
		}
		audited(actor, proposal, type, lapse);
		return proposal;
	}

	/** Dismisses a proposed lapse, with a reason. The days stay where they are. */
	public TimeOffProposal dismiss(User actor, String proposalId, String reason) {
		access.requireKeeper(actor);
		TimeOffProposal proposal = claim(actor, proposalId, TimeOffProposal.Status.DISMISSED, requireReason(reason));
		audited(actor, proposal, types.findById(proposal.getTypeId()).orElse(null), 0);
		return proposal;
	}

	/**
	 * Moves an open proposal to its decision in one conditional write, so two keepers deciding at
	 * once cannot both book the lapse. 404 for an id nobody has, 409 for a decided one.
	 */
	private TimeOffProposal claim(User actor, String proposalId, TimeOffProposal.Status to, String reason) {
		TimeOffProposal decided = mongo.findAndModify(
				Query.query(Criteria.where("_id").is(proposalId).and("status").is(TimeOffProposal.Status.OPEN)),
				new Update().set("status", to).set("decidedBy", actor.getId()).set("decidedAt", clock.instant())
						.set("decisionReason", reason),
				FindAndModifyOptions.options().returnNew(true), TimeOffProposal.class);
		if (decided != null) {
			return decided;
		}
		if (!proposals.existsById(proposalId)) {
			throw ApiException.notFound("timeOffProposal");
		}
		throw ApiException.conflict("error.timeOff.proposalDecided");
	}

	private void audited(User actor, TimeOffProposal proposal, TimeOffType type, int lapse) {
		// The decision and the amount — never the reason, which may well name an illness (Art. 9).
		audit.event(AuditAction.TIME_OFF_PROPOSAL_DECIDED).actor(actor)
				.target(users.findById(proposal.getUserId()).orElse(null))
				.meta("proposal", proposal.getId())
				.meta("status", String.valueOf(proposal.getStatus()))
				.meta("type", type == null ? "" : String.valueOf(type.getKey()))
				.meta("year", String.valueOf(proposal.getYear()))
				.meta("milliDays", String.valueOf(lapse))
				.log();
	}

	private static String requireReason(String reason) {
		String text = reason == null ? "" : reason.strip();
		if (text.isEmpty()) {
			throw ApiException.badRequest("error.timeOff.reasonRequired");
		}
		return text.length() > TimeOffProposal.REASON_MAX ? text.substring(0, TimeOffProposal.REASON_MAX) : text;
	}

	// --- settling the leave of somebody who leaves --------------------------------------

	/**
	 * For every type with a quota: what the leaving year is worth with the leaving date, and what is
	 * then left to pay out (§ 5 and § 7 Abs. 4 BUrlG). Needs the leaving date.
	 */
	public List<Settlement> settlement(User actor, String userId) {
		access.requireKeeper(actor);
		User person = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
		TimeOffEmployment facts = employment.findByUserId(person.getId())
				.orElseThrow(() -> ApiException.badRequest("error.timeOff.leftOnMissing"));
		if (facts.getLeftOn() == null) {
			throw ApiException.badRequest("error.timeOff.leftOnMissing");
		}
		List<Settlement> rows = new ArrayList<>();
		for (TimeOffType type : types.findAll()) {
			if (!type.countsAgainstBalance() || type.isUnlimited()) {
				continue;
			}
			int year = TimeOffBalances.leaveYearOf(facts.getLeftOn(), type.yearAnchor());
			Map<String, Map<Integer, TimeOffYearFigures.Year>> sums =
					figures.sums(type.getId(), List.of(year), List.of(person.getId()));
			TimeOffYearFigures.Year now = TimeOffYearFigures.yearOf(sums, person.getId(), year);
			Optional<TimeOffEntitlement> grant = entitlements.findByUserIdAndTypeIdAndYear(person.getId(),
					type.getId(), year);
			if (grant.isEmpty() && now.byKind().isEmpty()) {
				continue;
			}
			int allowance = grant.map(TimeOffEntitlement::allowanceMilliDays).orElse(0);
			TimeOffBalances.Accrued worth = TimeOffBalances.accrue(
					TimeOffBalances.Rules.of(type).withAllowance(allowance), facts.getHiredOn(), facts.getLeftOn(),
					year);
			int accrued = TimeOffBalances.accruedBy(worth, type.accrual(), type.yearAnchor(), year,
					facts.getLeftOn());
			int booked = now.of(TimeOffLedgerEntry.Kind.ACCRUAL);
			int correction = grant.isPresent() ? accrued - booked : 0;
			int remaining = now.remaining();
			rows.add(new Settlement(type.getId(), year, worth.reason(), accrued, booked, correction, remaining,
					Math.max(0, remaining + correction)));
		}
		return rows;
	}

	/**
	 * Books a payout: days, never an amount of money, and never more than is left. The keeper's
	 * reason is required, like on any booking that takes days away.
	 */
	public TimeOffLedgerEntry payout(User actor, String userId, String typeId, int year, int milliDays, String reason) {
		access.requireKeeper(actor);
		User person = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
		TimeOffType type = types.findById(typeId).orElseThrow(() -> ApiException.notFound("timeOffType"));
		if (!type.countsAgainstBalance() || type.isUnlimited()) {
			throw ApiException.badRequest("error.timeOff.unlimitedHasNoBalance");
		}
		String text = requireReason(reason);
		int left = TimeOffYearFigures.yearOf(figures.sums(typeId, List.of(year), List.of(userId)), userId, year)
				.remaining();
		if (milliDays <= 0 || milliDays > left) {
			throw ApiException.badRequest("error.timeOff.payoutInvalid");
		}
		LocalDate on = employment.findByUserId(userId).map(TimeOffEmployment::getLeftOn)
				.orElse(LocalDate.now(clock));
		TimeOffLedgerEntry entry = balances.book(TimeOffLedgerEntry.builder()
				.userId(person.getId()).typeId(typeId).year(year).kind(TimeOffLedgerEntry.Kind.PAYOUT)
				.milliDays(-milliDays).effectiveOn(on == null ? LocalDate.now(clock) : on)
				.reason(text).actorId(actor.getId()).build());
		audit.event(AuditAction.TIME_OFF_LEDGER_BOOKED).actor(actor).target(person)
				.meta("kind", TimeOffLedgerEntry.Kind.PAYOUT.name())
				.meta("type", String.valueOf(type.getKey()))
				.meta("year", String.valueOf(year))
				.meta("milliDays", String.valueOf(-milliDays))
				.log();
		return entry;
	}
}
