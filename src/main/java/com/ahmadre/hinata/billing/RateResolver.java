package com.ahmadre.hinata.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Which rate prices one entry: a pure function over the rates of one kind (HIN-96).
 *
 * <p>Precedence, most specific first: the issue, the person within the project, the person, the
 * project, the person's team, the instance default. The first target with a rate in force on the
 * entry's <em>day</em> wins — never on the day a report is read, so a rate change in the middle of
 * a month values the first half at the old rate and the second at the new one.
 *
 * <p>Somebody in two teams with a rate each is priced at the more recently started of the two
 * rates in force (then the more recently written). That is the rule an administrator can predict
 * from the rate list; any order of teams would be one nobody sees.
 *
 * <p>Built once per report or invoice from every rate of the kind, then asked once per entry
 * without a read. Rates are few — a few per project and person — so the lookup is a map access
 * and a short scan.
 */
public final class RateResolver {

	/** What decides an entry's rate. {@code teamIds} are the teams the person belongs to. */
	public record Facts(String userId, String projectId, String issueId, LocalDate date, Collection<String> teamIds) {
	}

	private record Target(BillingRate.Scope scope, String scopeId, String secondaryId) {
	}

	private static final Comparator<BillingRate> NEWEST = Comparator
			.comparing(BillingRate::getValidFrom)
			.thenComparing(rate -> Objects.requireNonNullElse(rate.getCreatedAt(), Instant.EPOCH));

	private final Map<Target, List<BillingRate>> byTarget;

	private RateResolver(Map<Target, List<BillingRate>> byTarget) {
		this.byTarget = byTarget;
	}

	/** A resolver over [rates], which must all be of one kind. */
	public static RateResolver of(Collection<BillingRate> rates) {
		Map<Target, List<BillingRate>> byTarget = new HashMap<>();
		for (BillingRate rate : rates) {
			byTarget.computeIfAbsent(new Target(rate.getScope(), rate.getScopeId(), rate.getSecondaryId()),
					target -> new ArrayList<>()).add(rate);
		}
		return new RateResolver(byTarget);
	}

	/** The rate in force for [facts], or null when no target has one on that day. */
	public BillingRate resolve(Facts facts) {
		LocalDate day = facts.date();
		BillingRate found;
		if (facts.issueId() != null && (found = at(BillingRate.Scope.ISSUE, facts.issueId(), null, day)) != null) {
			return found;
		}
		if (facts.projectId() != null && facts.userId() != null
				&& (found = at(BillingRate.Scope.PROJECT_MEMBER, facts.projectId(), facts.userId(), day)) != null) {
			return found;
		}
		if (facts.userId() != null && (found = at(BillingRate.Scope.USER, facts.userId(), null, day)) != null) {
			return found;
		}
		if (facts.projectId() != null
				&& (found = at(BillingRate.Scope.PROJECT, facts.projectId(), null, day)) != null) {
			return found;
		}
		BillingRate team = null;
		if (facts.teamIds() != null) {
			for (String teamId : facts.teamIds()) {
				BillingRate candidate = at(BillingRate.Scope.TEAM, teamId, null, day);
				if (candidate != null && (team == null || NEWEST.compare(candidate, team) > 0)) {
					team = candidate;
				}
			}
		}
		if (team != null) {
			return team;
		}
		return at(BillingRate.Scope.DEFAULT, null, null, day);
	}

	private BillingRate at(BillingRate.Scope scope, String scopeId, String secondaryId, LocalDate day) {
		List<BillingRate> rates = byTarget.get(new Target(scope, scopeId, secondaryId));
		if (rates == null) {
			return null;
		}
		BillingRate found = null;
		for (BillingRate rate : rates) {
			// Spans of one target do not overlap (the write path refuses it); should stored
			// data say otherwise, the most recently started rate wins rather than the first read.
			if (rate.covers(day) && (found == null || NEWEST.compare(rate, found) > 0)) {
				found = rate;
			}
		}
		return found;
	}
}
