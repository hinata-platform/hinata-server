/**
 * Absence management 2.0: what kinds of absence exist, how much of each somebody is entitled to,
 * what their balance is, and (from stage A2) who approves a request for one.
 *
 * <p>The division of labour with {@code availability} is the thing to keep hold of.
 * {@code availability} stores <b>that</b> somebody is away — the {@code time_off} documents that
 * shape capacity — and it is the only thing capacity needs. This package stores the rules
 * <b>around</b> that: a vacation type with an annual allowance, a booking that reduces a balance,
 * a request somebody approved. The direction is {@code timeoff -> availability} and never the
 * reverse, so switching absence management off leaves capacity exactly as it was.
 *
 * <p><b>Two units, two questions.</b> A balance counts working days, in thousandths
 * ({@code milliDays}: half a day is 500), because § 3 BUrlG grants leave in working days. Capacity
 * counts minutes, because that is what a working pattern holds. The same absence answers both, and
 * neither number is ever computed from the other — an hour is not a fraction of an entitlement.
 *
 * <p><b>A balance is the sum of a ledger.</b> {@code time_off_ledger} is append-only and nothing
 * stores a running total: a correction is a counter-booking, never an overwrite, and "why do I
 * have 24 rather than 30?" is a question the data can answer.
 *
 * <p><b>Sickness is reported, not requested (R11).</b> Types of the {@code SICK} kind can never be
 * made subject to approval, take effect at once including retroactively, and require no reason,
 * no certificate and no upload — § 5 EFZG knows a notification, not a permission, and a sick note
 * is health data under Art. 9 DSGVO which has no business in a project tool. Since 2023 an
 * employer retrieves the eAU from the health insurer (§ 109 SGB IV), not from here.
 *
 * <p>Everything in this package is behind the {@code absence_management} feature flag
 * ({@link com.ahmadre.hinata.timeoff.TimeOffSettings}), which an administrator switches on
 * deliberately and which is off on a fresh instance.
 */
package com.ahmadre.hinata.timeoff;
