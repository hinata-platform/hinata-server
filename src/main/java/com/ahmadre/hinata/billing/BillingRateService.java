package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writing and reading rates (HIN-96).
 *
 * <p>One target never has two rates on the same day, of the same kind: the write is refused with
 * 409 rather than letting a report pick one. There is one convenience, because it is the change
 * people actually make — "from the first of next month the rate is 95": a new open-ended rate whose
 * start falls inside the running open-ended rate closes that one the day before. Everything else
 * that overlaps is the caller's to sort out, by shortening or removing a rate first.
 *
 * <p>Revenue rates for a project, its members and its issues are the project lead's; team, person
 * and default revenue rates and every cost rate are an administrator's (R7: what a person costs is
 * nobody else's business).
 */
@Service
@RequiredArgsConstructor
public class BillingRateService {

	/** Largest page. */
	public static final int PAGE_MAX = 100;

	/** Most rates one target's timeline shows. */
	static final int TIMELINE_MAX = 200;

	/** Highest hourly rate, in cents: ten million per hour is past any honest figure. */
	static final long AMOUNT_MAX = 1_000_000_000L;

	private final BillingRateRepository rates;
	private final BillingAccess access;
	private final BillingSettings settings;
	private final BillingNames names;
	private final MongoTemplate mongo;
	private final AuditService audit;
	private final Clock clock;

	/** Whether a rate is in force today, starts later, or has ended. */
	public enum Status {
		ACTIVE, PLANNED, ENDED
	}

	/** What a client sends to create a rate. */
	public record Draft(BillingRate.Kind kind, BillingRate.Scope scope, String scopeId, String secondaryId,
			Long amountCents, LocalDate validFrom, LocalDate validTo) {
	}

	/** What a client sends to change one. Absent fields stay; {@code openEnded} clears the end. */
	public record Change(Long amountCents, LocalDate validFrom, LocalDate validTo, Boolean openEnded) {
	}

	/**
	 * One rate as a client reads it, with its target named.
	 *
	 * @param targetLabel  the team's, project's, person's or issue's name; null for the default
	 * @param targetDetail a second line: a project key, an issue's readable id, a username
	 * @param memberLabel  the person of a member rate
	 */
	public record RateView(String id, BillingRate.Kind kind, BillingRate.Scope scope, String scopeId,
			String secondaryId, String projectId, long amountCents, String currency, LocalDate validFrom,
			LocalDate validTo, Status status, String targetLabel, String targetDetail, String memberLabel) {
	}

	// --- reading ------------------------------------------------------------

	public Page<RateView> page(User viewer, BillingRate.Kind kind, BillingRate.Scope scope, String scopeId,
			String secondaryId, String projectId, Status status, int page, int size) {
		BillingAccess.Reach reach = access.require(viewer);
		List<Criteria> all = new ArrayList<>();
		if (kind == BillingRate.Kind.COST) {
			access.requireCosts(reach);
		}
		if (!reach.admin()) {
			// A lead reads the revenue rates of the projects they lead, and nothing else.
			all.add(Criteria.where("kind").is(BillingRate.Kind.BILLABLE));
			if (projectId != null && !reach.leads(projectId)) {
				throw ApiException.forbidden("error.billing.notLead");
			}
			all.add(Criteria.where("projectId").in(projectId != null ? List.of(projectId) : reach.ledProjects()));
		}
		else if (projectId != null) {
			all.add(Criteria.where("projectId").is(projectId));
		}
		if (kind != null) {
			all.add(Criteria.where("kind").is(kind));
		}
		if (scope != null) {
			all.add(Criteria.where("scope").is(scope));
		}
		if (scopeId != null) {
			all.add(Criteria.where("scopeId").is(scopeId));
		}
		if (secondaryId != null) {
			all.add(Criteria.where("secondaryId").is(secondaryId));
		}
		LocalDate today = today();
		if (status != null) {
			all.add(switch (status) {
				case ACTIVE -> new Criteria().andOperator(Criteria.where("validFrom").lte(today),
						new Criteria().orOperator(Criteria.where("validTo").is(null),
								Criteria.where("validTo").gte(today)));
				case PLANNED -> Criteria.where("validFrom").gt(today);
				case ENDED -> Criteria.where("validTo").lt(today);
			});
		}
		Query query = Query.query(all.isEmpty() ? new Criteria() : new Criteria().andOperator(all));
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, 10_000), Math.clamp(size, 1, PAGE_MAX),
				Sort.by(Sort.Order.asc("kind"), Sort.Order.asc("scope"), Sort.Order.asc("scopeId"),
						Sort.Order.asc("secondaryId"), Sort.Order.desc("validFrom"), Sort.Order.asc("_id")));
		List<BillingRate> found = mongo.find(Query.of(query).with(pageable), BillingRate.class);
		return PageableExecutionUtils.getPage(views(found), pageable,
				() -> mongo.count(query, BillingRate.class));
	}

	/** Every rate of one target, newest start first: what the timeline of a target shows. */
	public List<RateView> timeline(User viewer, BillingRate.Kind kind, BillingRate.Scope scope, String scopeId,
			String secondaryId) {
		BillingAccess.Reach reach = access.require(viewer);
		authorize(reach, kind, scope, projectOf(scope, scopeId), false);
		Query query = Query.query(Criteria.where("kind").is(kind).and("scope").is(scope).and("scopeId").is(scopeId)
						.and("secondaryId").is(secondaryId))
				.with(Sort.by(Sort.Order.desc("validFrom"))).limit(TIMELINE_MAX);
		return views(mongo.find(query, BillingRate.class));
	}

	// --- writing ------------------------------------------------------------

	public RateView create(User viewer, Draft draft) {
		BillingAccess.Reach reach = access.require(viewer);
		if (draft.kind() == null || draft.scope() == null) {
			throw ApiException.badRequest("error.billing.rate.target");
		}
		String scopeId = blankToNull(draft.scopeId());
		String secondaryId = blankToNull(draft.secondaryId());
		String projectId = checkTarget(draft.scope(), scopeId, secondaryId);
		authorize(reach, draft.kind(), draft.scope(), projectId, true);
		long amount = amount(draft.amountCents());
		if (draft.validFrom() == null) {
			throw ApiException.badRequest("error.billing.rate.validFrom");
		}
		checkSpan(draft.validFrom(), draft.validTo());
		List<BillingRate> existing = rates.findByKindAndScopeAndScopeIdAndSecondaryIdOrderByValidFromAsc(
				draft.kind(), draft.scope(), scopeId, secondaryId);
		BillingRate superseded = supersedable(existing, draft.validFrom(), draft.validTo());
		for (BillingRate other : existing) {
			if (other != superseded && other.overlaps(draft.validFrom(), draft.validTo())) {
				throw overlap(other);
			}
		}
		if (superseded != null) {
			superseded.setValidTo(draft.validFrom().minusDays(1));
			stamp(superseded, viewer);
			save(superseded);
		}
		BillingRate rate = BillingRate.builder()
				.kind(draft.kind()).scope(draft.scope()).scopeId(scopeId).secondaryId(secondaryId)
				.projectId(projectId).amountCents(amount).currency(settings.currency())
				.validFrom(draft.validFrom()).validTo(draft.validTo())
				.createdBy(viewer.getId()).createdAt(clock.instant())
				.build();
		BillingRate saved = rates.save(rate);
		audited(AuditAction.BILLING_RATE_CREATED, viewer, saved, superseded);
		return views(List.of(saved)).getFirst();
	}

	public RateView update(User viewer, String id, Change change) {
		BillingAccess.Reach reach = access.require(viewer);
		BillingRate rate = rates.findById(id).orElseThrow(() -> ApiException.notFound("billingRate"));
		hideUnless(reach, rate);
		authorize(reach, rate.getKind(), rate.getScope(), rate.getProjectId(), true);
		if (change.amountCents() != null) {
			rate.setAmountCents(amount(change.amountCents()));
		}
		if (change.validFrom() != null) {
			rate.setValidFrom(change.validFrom());
		}
		if (Boolean.TRUE.equals(change.openEnded())) {
			rate.setValidTo(null);
		}
		else if (change.validTo() != null) {
			rate.setValidTo(change.validTo());
		}
		checkSpan(rate.getValidFrom(), rate.getValidTo());
		for (BillingRate other : rates.findByKindAndScopeAndScopeIdAndSecondaryIdOrderByValidFromAsc(rate.getKind(),
				rate.getScope(), rate.getScopeId(), rate.getSecondaryId())) {
			if (!other.getId().equals(rate.getId()) && other.overlaps(rate.getValidFrom(), rate.getValidTo())) {
				throw overlap(other);
			}
		}
		stamp(rate, viewer);
		BillingRate saved = save(rate);
		audited(AuditAction.BILLING_RATE_UPDATED, viewer, saved, null);
		return views(List.of(saved)).getFirst();
	}

	public void delete(User viewer, String id) {
		BillingAccess.Reach reach = access.require(viewer);
		BillingRate rate = rates.findById(id).orElseThrow(() -> ApiException.notFound("billingRate"));
		hideUnless(reach, rate);
		authorize(reach, rate.getKind(), rate.getScope(), rate.getProjectId(), true);
		rates.delete(rate);
		audited(AuditAction.BILLING_RATE_DELETED, viewer, rate, null);
	}

	/** Every rate of one kind: what a report or an invoice resolves against. Rates are few. */
	List<BillingRate> all(BillingRate.Kind kind) {
		return mongo.find(Query.query(Criteria.where("kind").is(kind)), BillingRate.class);
	}

	// --- rules --------------------------------------------------------------

	/**
	 * The running open-ended rate a new open-ended rate starting later replaces from its start on,
	 * or null. Only that case: a rate inserted into the middle of a span would have to split it,
	 * and splitting somebody's rate without being asked is not a convenience.
	 */
	static BillingRate supersedable(List<BillingRate> existing, LocalDate from, LocalDate to) {
		if (to != null) {
			return null;
		}
		for (BillingRate other : existing) {
			if (other.getValidTo() == null && other.getValidFrom().isBefore(from)) {
				return other;
			}
		}
		return null;
	}

	/**
	 * Checks the target exists and returns the project a lead's permission is judged by. A member
	 * rate names a project and a person; an issue rate takes its issue's project.
	 */
	private String checkTarget(BillingRate.Scope scope, String scopeId, String secondaryId) {
		boolean needsScopeId = scope != BillingRate.Scope.DEFAULT;
		boolean needsSecondary = scope == BillingRate.Scope.PROJECT_MEMBER;
		if (needsScopeId != (scopeId != null) || needsSecondary != (secondaryId != null)) {
			throw ApiException.badRequest("error.billing.rate.target");
		}
		boolean found = switch (scope) {
			case DEFAULT -> true;
			case TEAM -> names.exists(Team.class, scopeId);
			case PROJECT -> names.exists(Project.class, scopeId);
			case USER -> names.exists(User.class, scopeId);
			case PROJECT_MEMBER -> names.exists(Project.class, scopeId) && names.exists(User.class, secondaryId);
			case ISSUE -> names.exists(Issue.class, scopeId);
		};
		if (!found) {
			throw ApiException.badRequest("error.billing.rate.target");
		}
		return projectOf(scope, scopeId);
	}

	private String projectOf(BillingRate.Scope scope, String scopeId) {
		return switch (scope) {
			case PROJECT, PROJECT_MEMBER -> scopeId;
			case ISSUE -> scopeId == null ? null : names.projectOfIssue(scopeId);
			default -> null;
		};
	}

	/** Costs are an administrator's; revenue of a project's own targets also its lead's. */
	private void authorize(BillingAccess.Reach reach, BillingRate.Kind kind, BillingRate.Scope scope,
			String projectId, boolean write) {
		if (reach.admin()) {
			return;
		}
		if (kind == BillingRate.Kind.COST) {
			throw ApiException.forbidden("error.billing.costsForbidden");
		}
		if (!scope.leadWritable() || !reach.leads(projectId)) {
			throw ApiException.forbidden(write ? "error.billing.rate.notYours" : "error.billing.notLead");
		}
	}

	/** A rate the reader may not read is a rate that does not exist for them. */
	private static void hideUnless(BillingAccess.Reach reach, BillingRate rate) {
		if (reach.admin()) {
			return;
		}
		if (rate.getKind() != BillingRate.Kind.BILLABLE || !reach.leads(rate.getProjectId())) {
			throw ApiException.notFound("billingRate");
		}
	}

	private static long amount(Long cents) {
		if (cents == null || cents < 0 || cents > AMOUNT_MAX) {
			throw ApiException.badRequest("error.billing.rate.amount");
		}
		return cents;
	}

	private static void checkSpan(LocalDate from, LocalDate to) {
		if (to != null && to.isBefore(from)) {
			throw ApiException.badRequest("error.billing.rate.span");
		}
	}

	private static ApiException overlap(BillingRate other) {
		return ApiException.conflict("error.billing.rate.overlap", Map.of("rateId", other.getId(),
				"validFrom", String.valueOf(other.getValidFrom()),
				"validTo", String.valueOf(other.getValidTo())));
	}

	private BillingRate save(BillingRate rate) {
		try {
			return rates.save(rate);
		}
		catch (OptimisticLockingFailureException raced) {
			throw ApiException.conflict("error.billing.rate.changed");
		}
	}

	private void stamp(BillingRate rate, User viewer) {
		rate.setUpdatedBy(viewer.getId());
		rate.setUpdatedAt(clock.instant());
	}

	/**
	 * The record of a rate change. The amount of a cost rate is left out: the log is read more
	 * widely than the rates, and a person's cost is the one figure R7 keeps to administrators.
	 */
	private void audited(AuditAction action, User viewer, BillingRate rate, BillingRate superseded) {
		AuditService.Entry entry = audit.event(action).actor(viewer)
				.target(rate.getId(), rate.getScope() + ":" + Objects.toString(rate.getScopeId(), "default"))
				.meta("kind", rate.getKind().name())
				.meta("scope", rate.getScope().name())
				.meta("validFrom", String.valueOf(rate.getValidFrom()))
				.meta("validTo", String.valueOf(rate.getValidTo()));
		if (rate.getKind() == BillingRate.Kind.BILLABLE) {
			entry.meta("amountCents", String.valueOf(rate.getAmountCents()));
		}
		if (superseded != null) {
			entry.meta("closed", superseded.getId());
		}
		entry.log();
	}

	private List<RateView> views(List<BillingRate> found) {
		List<String> projects = new ArrayList<>();
		List<String> users = new ArrayList<>();
		List<String> teams = new ArrayList<>();
		List<String> issues = new ArrayList<>();
		for (BillingRate rate : found) {
			switch (rate.getScope()) {
				case PROJECT -> projects.add(rate.getScopeId());
				case PROJECT_MEMBER -> {
					projects.add(rate.getScopeId());
					users.add(rate.getSecondaryId());
				}
				case USER -> users.add(rate.getScopeId());
				case TEAM -> teams.add(rate.getScopeId());
				case ISSUE -> issues.add(rate.getScopeId());
				case DEFAULT -> {
				}
			}
		}
		Map<String, BillingNames.Name> projectNames = names.projects(projects);
		Map<String, BillingNames.Name> userNames = names.users(users);
		Map<String, BillingNames.Name> teamNames = names.teams(teams);
		Map<String, BillingNames.Name> issueNames = names.issues(issues);
		LocalDate today = today();
		List<RateView> views = new ArrayList<>(found.size());
		for (BillingRate rate : found) {
			BillingNames.Name target = switch (rate.getScope()) {
				case PROJECT, PROJECT_MEMBER -> projectNames.get(rate.getScopeId());
				case USER -> userNames.get(rate.getScopeId());
				case TEAM -> teamNames.get(rate.getScopeId());
				case ISSUE -> issueNames.get(rate.getScopeId());
				case DEFAULT -> null;
			};
			BillingNames.Name member = rate.getScope() == BillingRate.Scope.PROJECT_MEMBER
					? userNames.get(rate.getSecondaryId()) : null;
			Status status = rate.getValidFrom().isAfter(today) ? Status.PLANNED
					: rate.getValidTo() != null && rate.getValidTo().isBefore(today) ? Status.ENDED : Status.ACTIVE;
			views.add(new RateView(rate.getId(), rate.getKind(), rate.getScope(), rate.getScopeId(),
					rate.getSecondaryId(), rate.getProjectId(), rate.getAmountCents(), rate.getCurrency(),
					rate.getValidFrom(), rate.getValidTo(), status, target == null ? null : target.label(),
					target == null ? null : target.detail(), member == null ? null : member.label()));
		}
		return views;
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
