package com.ahmadre.hinata.timetracking;

/**
 * What a project's money budget has used (HIN-155), answered by billing.
 *
 * <p>Time tracking never asks billing a question directly (billing → timetracking, never the other
 * way, see ModuleBoundaryTest): it declares what it needs here and billing implements it, the way
 * the deletion cascade asks a hook billing provides. Without billing on the classpath, or with it
 * switched off, there is no money budget at all.
 */
public interface MoneyBudgets {

	/** Whether billing is switched on, so money budgets exist. */
	boolean enabled();

	/** The instance's currency, ISO 4217. */
	String currency();

	/**
	 * Cents a project has used: billable entries at their revenue rates for {@code BILLABLE}, every
	 * entry at its cost rate for {@code COST}, each valued on its own day like a billing report.
	 */
	long usedCents(String projectId, ProjectTimeSettings.RateKind kind);
}
