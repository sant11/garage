package com.example.garageops.dashboard;

/**
 * One row of the dashboard's "vacant garages" section (US-01, S-06): an active garage with no
 * contract running today, plus how long it has stood empty. An off-session-safe value type — it
 * carries the garage and location labels as already-resolved {@code String}s rather than the lazy
 * entities, so it crosses the view boundary freely under {@code open-in-view=false} (the
 * {@link com.example.garageops.payments.OverdueRow} pattern).
 *
 * @param garageId     the vacant garage, so the dashboard can drill through to {@code garages/:id}
 * @param garageLabel  the garage's display label
 * @param locationName the name of the garage's location
 * @param daysVacant   whole days the garage has been empty, from its vacancy-since date to today
 */
public record VacantGarageRow(Long garageId, String garageLabel, String locationName, long daysVacant) {
}
