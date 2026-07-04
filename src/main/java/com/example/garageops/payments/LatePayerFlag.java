package com.example.garageops.payments;

/**
 * The derived frequent-late-payer verdict for one tenant (FR-020 / S-07). An off-session-safe value
 * type — plain ints and a boolean, no entity references — so it crosses the view boundary freely
 * under {@code open-in-view=false}, mirroring {@link OverdueRow}. Carries the threshold and window it
 * was judged against so the profile badge can render a self-describing tooltip without re-reading
 * config.
 *
 * @param flagged      {@code true} when {@code eventCount >= minEvents}
 * @param eventCount   overdue (contract, period) events found in the window
 * @param windowMonths the look-back window the count was taken over
 * @param minEvents    the threshold {@code eventCount} was compared against
 */
public record LatePayerFlag(boolean flagged, int eventCount, int windowMonths, int minEvents) {
}
