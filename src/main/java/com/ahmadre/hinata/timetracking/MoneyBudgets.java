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
	 * What a project's money budget has used, and the recorded minutes it could not count (HIN-158):
	 * {@code unbilledMinutes} are entries a revenue budget leaves out because they are not billable,
	 * {@code unratedMinutes} entries no rate was in force for on their day. Both say why a figure is
	 * lower than the hours suggest, so it never sits at zero without a reason.
	 */
	record Used(long cents, long unbilledMinutes, long unratedMinutes) {

		public static final Used NONE = new Used(0, 0, 0);
	}

	/**
	 * Cents a project has used: billable entries at their revenue rates for {@code BILLABLE}, every
	 * entry at its cost rate for {@code COST}, each valued on its own day like a billing report.
	 */
	default long usedCents(String projectId, ProjectTimeSettings.RateKind kind) {
		return used(java.util.Map.of(projectId, kind)).getOrDefault(projectId, Used.NONE).cents();
	}

	/** What each of several projects has used, valued as {@link #usedCents} values one. */
	java.util.Map<String, Used> used(java.util.Map<String, ProjectTimeSettings.RateKind> projects);
}
