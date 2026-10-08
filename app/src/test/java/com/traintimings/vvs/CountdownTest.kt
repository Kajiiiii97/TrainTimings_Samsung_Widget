package com.traintimings.vvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CountdownTest {

    private val now = Instant.parse("2026-10-08T12:00:00Z")

    private fun dep(line: String, dest: String, minutes: Long, cancelled: Boolean = false) = Departure(
        line = line,
        destination = dest,
        productClass = 3,
        planned = now.plusSeconds(minutes * 60),
        estimated = null,
        cancelled = cancelled,
        platform = "",
    )

    @Test
    fun groupsByLineAndDirectionWithNextAndAfter() {
        val rows = Countdown.group(
            listOf(
                dep("U14", "Vaihingen", 17),
                dep("U12", "Dürrlewang", 3),
                dep("U14", "Vaihingen", 7),
                dep("U12", "Dürrlewang", 13),
                dep("U12", "Dürrlewang", 23),
                dep("U12", "Remseck", 9),
            ),
            now,
        )
        assertEquals(listOf("Dürrlewang", "Vaihingen", "Remseck"), rows.map { it.next.destination })
        assertEquals(3, Countdown.minutesUntil(rows[0].next.time, now))
        assertEquals(13, Countdown.minutesUntil(rows[0].after!!.time, now))
        assertEquals(17, Countdown.minutesUntil(rows[1].after!!.time, now))
        assertNull(rows[2].after)
    }

    @Test
    fun skipsCancelledAndDepartedTrains() {
        val rows = Countdown.group(
            listOf(
                dep("S1", "Herrenberg", -2),
                dep("S1", "Herrenberg", 4, cancelled = true),
                dep("S1", "Herrenberg", 19),
                dep("S1", "Herrenberg", 34),
            ),
            now,
        )
        assertEquals(1, rows.size)
        assertEquals(19, Countdown.minutesUntil(rows[0].next.time, now))
        assertEquals(34, Countdown.minutesUntil(rows[0].after!!.time, now))
    }

    @Test
    fun minutesRoundDownAndNeverGoNegative() {
        assertEquals(0, Countdown.minutesUntil(now.plusSeconds(59), now))
        assertEquals(1, Countdown.minutesUntil(now.plusSeconds(60), now))
        assertEquals(0, Countdown.minutesUntil(now.minusSeconds(20), now))
    }
}
