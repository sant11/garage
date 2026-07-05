package com.example.garageops.payments;

import java.math.BigDecimal;

/**
 * One row of the Dues view (US-01, {@code prd.md:54}): a currently-overdue garage with the columns
 * the owner needs to chase payment. An off-session-safe value type — it carries the garage label and
 * tenant name as already-resolved {@code String}s rather than the lazy entities, so it crosses the
 * view boundary freely under {@code open-in-view=false}.
 *
 * @param contractId  the overdue contract
 * @param garageId    the contract's garage, so the Dues view can drill through to {@code garages/:id}
 * @param garageLabel the garage's display label
 * @param tenantName  the renting tenant's name
 * @param amountDue   the contract's cumulative balance ({@code periodsDue × monthlyRent − totalPaid},
 *                    never negative) — may exceed one month's rent when several periods are in arrears
 * @param daysOverdue whole days past the due date of the oldest period not fully covered by payments
 *                    (FIFO credit)
 */
public record OverdueRow(Long contractId, Long garageId, String garageLabel, String tenantName,
		BigDecimal amountDue, long daysOverdue) {
}
