/**
 * Shift planning (HIN-27): events, the shifts inside them and who covers each one.
 *
 * <p>An optional module, off on a fresh instance and switched on by an administrator without a
 * restart. Everything the module owns sits behind {@link com.ahmadre.hinata.shifts.ShiftSettings}:
 * the flag {@code shift_planning} that {@code /api/v1/meta} publishes, the
 * {@link com.ahmadre.hinata.shifts.ShiftPlanningGate} in front of the HTTP routes, and the
 * {@code requireEnabled()} every service entry and every job asks, because jobs and MCP tools pass
 * no interceptor.
 *
 * <p><b>Transactions are a requirement, not an option.</b> Taking the last place on a shift runs in
 * a Mongo transaction (HIN-43). On an instance without {@code hinata.mongodb.transactions-enabled}
 * (no replica set) the module stays off whatever the switch says, {@code /meta} reports
 * {@code shiftPlanningAvailable: false}, and the server logs why at start.
 *
 * <p><b>The direction is one way</b>, and {@code timetracking.ModuleBoundaryTest} holds it. This
 * package may read {@code project}, {@code user}, {@code me}, {@code auth}, {@code notification},
 * {@code availability}, {@code common}, {@code audit}, {@code ics}, {@code setup} and
 * {@code config}, plus {@code issue.export} for the export and {@code mcp.ScopeGuard},
 * {@code mcp.McpErrors} and {@code pat.Scopes} for the MCP tools. Nothing from time tracking,
 * absences, billing or templates: a volunteer covering a stand records no working time. Nothing
 * outside reaches in, bar the wire contracts ({@code AuditAction}, {@code Notification}) and the
 * demo seeder, which may name the stored entities and nothing else. No shift field is ever added
 * to a project, an issue, a team or a user; the module keeps its own collections.
 *
 * <p>Notifications and audit records from inside a transaction are delivered after the commit
 * ({@code @TransactionalEventListener(phase = AFTER_COMMIT)}), never from the transactional method
 * itself: a rolled-back assignment must not have told anybody they got the shift.
 *
 * <p>The name is {@code shifts}, not {@code schedule}: that word already means working-time
 * patterns in {@code availability} and template deadlines under {@code /projects/{id}/schedule}.
 */
package com.ahmadre.hinata.shifts;
