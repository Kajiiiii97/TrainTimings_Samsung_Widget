package com.traintimings.vvs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RefresherTest {

    private val now = Instant.parse("2026-10-15T08:00:00Z")

    private fun dep(line: String, minutes: Long, delay: Long = 0, cancelled: Boolean = false): Departure {
        val planned = now.plusSeconds(minutes * 60)
        return Departure(
            line = line,
            destination = "X",
            productClass = 5,
            planned = planned,
            estimated = if (delay > 0) planned.plusSeconds(delay * 60) else null,
            cancelled = cancelled,
            platform = "",
        )
    }

    @Test
    fun quickFetchUpdatesTheNearWindowAndKeepsLaterTrips() {
        val cached = listOf(dep("54", 2), dep("42", 5), dep("54", 12), dep("54", 40), dep("42", 55))
        // Quick fetch covers up to minute 12: the 54 at 2 is now 3 min late, the 42 at 5 was cancelled.
        val quick = listOf(dep("54", 2, delay = 3), dep("42", 5, cancelled = true), dep("54", 12))
        val merged = Refresher.merge(cached, quick)

        assertEquals(listOf(2L, 5L, 12L, 40L, 55L), merged.map { (it.planned.epochSecond - now.epochSecond) / 60 })
        assertEquals(3, merged.first().delayMinutes)
        assertEquals(true, merged[1].cancelled)
    }

    @Test
    fun emptyQuickFetchKeepsTheCache() {
        val cached = listOf(dep("54", 2))
        assertEquals(cached, Refresher.merge(cached, emptyList()))
    }

    @Test
    fun decidesBetweenQuickAndFullFetches() {
        val trips = listOf(dep("54", 5), dep("54", 90))
        fun cache(fetchedAgoS: Long, fullAgoS: Long, departures: List<Departure> = trips) =
            DepartureCache(now.minusSeconds(fetchedAgoS), departures, null, fullAt = now.minusSeconds(fullAgoS))

        assertEquals(Refresher.Kind.FULL, Refresher.due(null, now))
        assertEquals(Refresher.Kind.NONE, Refresher.due(cache(10, 10), now))
        assertEquals(Refresher.Kind.QUICK, Refresher.due(cache(30, 120), now))
        assertEquals(Refresher.Kind.FULL, Refresher.due(cache(30, 11 * 60), now))
        // Cached trips run out within 20 minutes: look further ahead now.
        assertEquals(Refresher.Kind.FULL, Refresher.due(cache(30, 120, listOf(dep("54", 5))), now))
    }
}
