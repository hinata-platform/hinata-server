/**
 * Billing (HIN-96): hourly rates, labour costs, the billing, profitability and utilization
 * reports, and invoices.
 *
 * <p>Built on top of time tracking and never underneath it: {@code billing -> timetracking ->
 * availability}, and nothing outside this package depends on it (ModuleBoundaryTest). What time
 * tracking has to know about an invoice — that an entry is billed — is a mark on the entry
 * ({@code WorkItem.invoiceId}) that this package writes, so recording time never asks billing a
 * question. What the core has to know — that a project with issued invoices cannot be deleted —
 * arrives through {@code deletion.ProjectDeletionHook}, which this package implements.
 *
 * <p>Everything here exists only while {@code billingEnabled} is on, over an extended module that
 * is on ({@link com.ahmadre.hinata.billing.BillingSettings}). Costs per person are an
 * administrator's alone; a lead sees revenue for the projects they lead and nothing about what a
 * person costs (R7).
 */
package com.ahmadre.hinata.billing;
