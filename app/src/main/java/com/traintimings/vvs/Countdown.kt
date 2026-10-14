package com.traintimings.vvs

import java.time.Duration
import java.time.Instant

/** One widget tile: a line + direction with its next train and the one after. */
data class UpcomingLine(val next: Departure, val after: Departure?)

object Countdown {

    /**
     * Groups departures by stop + line + direction (short-turning trips going the same way share a group),
     * skipping cancelled trips, soonest group first.
     */
    fun group(departures: List<Departure>, now: Instant): List<UpcomingLine> =
        departures
            .filter { !it.cancelled && !it.time.isBefore(now.minusSeconds(30)) }
            .sortedBy { it.time }
            .groupBy { it.stopId to it.groupKey }
            .values
            .map { UpcomingLine(it[0], it.getOrNull(1)) }
            .sortedBy { it.next.time }

    /**
     * The tiles to show.
     *
     * With a user [order]: tiles follow it (a whole-stop entry keeps its lines together in line
     * order) and the first [limit] with a train coming are shown.
     * Without one: the [limit] soonest lines, laid out in a fixed line order so a tile doesn't jump
     * around the grid every time a train leaves.
     */
    fun tiles(
        departures: List<Departure>,
        now: Instant,
        limit: Int,
        order: List<TileKey> = emptyList(),
    ): List<UpcomingLine> {
        val byLine = LineOrder.comparator<UpcomingLine>({ it.next.lineDirection }, { it.next.productClass })
        val groups = group(departures, now)
        if (order.isEmpty()) return groups.take(limit).sortedWith(byLine)

        fun rank(t: UpcomingLine): Int {
            val exact = order.indexOfFirst { it.line != null && it.matches(t.next) }
            if (exact >= 0) return exact
            val wholeStop = order.indexOfFirst { it.line == null && it.matches(t.next) }
            return if (wholeStop >= 0) wholeStop else Int.MAX_VALUE
        }
        return groups.sortedWith(compareBy<UpcomingLine> { rank(it) }.then(byLine)).take(limit)
    }

    /** Whole minutes until [time], rounded down, never negative. */
    fun minutesUntil(time: Instant, now: Instant): Long =
        Duration.between(now, time).seconds.coerceAtLeast(0) / 60
}
