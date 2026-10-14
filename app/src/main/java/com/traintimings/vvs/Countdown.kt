package com.traintimings.vvs

import java.time.Duration
import java.time.Instant

/** One widget tile: a line + direction with its next train and the one after. */
data class UpcomingLine(val next: Departure, val after: Departure?)

object Countdown {

    /** Groups departures by line + direction, skipping cancelled trips, soonest group first. */
    fun group(departures: List<Departure>, now: Instant): List<UpcomingLine> =
        departures
            .filter { !it.cancelled && !it.time.isBefore(now.minusSeconds(30)) }
            .sortedBy { it.time }
            .groupBy { it.lineDirection }
            .values
            .map { UpcomingLine(it[0], it.getOrNull(1)) }
            .sortedBy { it.next.time }

    /**
     * The tiles to show: the [limit] soonest lines, laid out in a fixed line order
     * so a tile doesn't jump around the grid every time a train leaves.
     */
    fun tiles(departures: List<Departure>, now: Instant, limit: Int): List<UpcomingLine> =
        group(departures, now)
            .take(limit)
            .sortedWith(LineOrder.comparator({ it.next.lineDirection }, { it.next.productClass }))

    /** Whole minutes until [time], rounded down, never negative. */
    fun minutesUntil(time: Instant, now: Instant): Long =
        Duration.between(now, time).seconds.coerceAtLeast(0) / 60
}
