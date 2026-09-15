/**
 * Who is available when: working-time patterns, absences and public holidays, and the capacity
 * that follows from them (HIN-91).
 *
 * <p>Planning information, not control. A pattern says how many hours somebody plans to work on a
 * weekday, an absence says that and how long somebody is away (vacation, sick, other, never why),
 * and a holiday calendar says which days are not ordinary working days. Capacity is the sum of the
 * pattern's hours over a window, less holidays and absences.
 *
 * <p><b>A marking is never a refusal (R9).</b> This package hands out data to display. Nothing in
 * it decides whether time may be recorded, and nothing that writes time may ask it: § 9 ArbZG
 * forbids work on a public holiday, not recording it, and § 16 Abs. 2 ArbZG requires Sunday and
 * holiday work to be recorded. {@code timetracking.ModuleBoundaryTest} holds the direction.
 *
 * <p>Two packages may read it: time tracking, which draws absences and holidays beside the
 * entries, and shift planning (HIN-43/45), which checks shifts against them. This package knows
 * neither. What it needs from time tracking, whether leads see their members' absences, arrives
 * through {@link com.ahmadre.hinata.availability.AvailabilityPolicy}.
 */
package com.ahmadre.hinata.availability;
