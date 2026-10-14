package com.traintimings.vvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CountdownTest {

    private val now = Instant.parse("2026-10-08T12:00:00Z")

    private fun dep(
        line: String,
        dest: String,
        minutes: Long,
        cancelled: Boolean = false,
        stop: String = "",
        dir: String = "",
    ) = Departure(
        line = line,
        destination = dest,
        productClass = 3,
        planned = now.plusSeconds(minutes * 60),
        estimated = null,
        cancelled = cancelled,
        platform = "",
        stopId = stop,
        direction = dir,
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

    @Test
    fun tilesKeepTheSoonestLinesInAFixedOrder() {
        val tiles = Countdown.tiles(
            listOf(
                dep("56", "Münster", 1),
                dep("U14", "Vaihingen", 2),
                dep("S1", "Herrenberg", 3),
                dep("U12", "Dürrlewang", 4),
                dep("U14", "Mühlhausen", 5),
                dep("S1", "Kirchheim", 6),
                dep("U12", "Remseck", 40),
            ),
            now,
            limit = 6,
        )
        // Remseck is the 7th soonest so it's dropped; the rest are sorted S-Bahn, Stadtbahn, bus.
        assertEquals(
            listOf("S1 Herrenberg", "S1 Kirchheim", "U12 Dürrlewang", "U14 Mühlhausen", "U14 Vaihingen", "56 Münster"),
            tiles.map { "${it.next.line} ${it.next.destination}" },
        )
    }

    @Test
    fun sameLineAtTwoStopsGetsTwoTiles() {
        val tiles = Countdown.tiles(
            listOf(
                dep("U14", "Vaihingen", 3, stop = "pragsattel"),
                dep("U14", "Vaihingen", 8, stop = "loewentor"),
                dep("U14", "Vaihingen", 13, stop = "pragsattel"),
            ),
            now,
            limit = 6,
        )
        assertEquals(2, tiles.size)
        assertEquals(setOf("pragsattel", "loewentor"), tiles.map { it.next.stopId }.toSet())
        assertEquals(13, Countdown.minutesUntil(tiles.first { it.next.stopId == "pragsattel" }.after!!.time, now))
    }

    @Test
    fun customOrderWinsOverSoonestAndKeepsWholeStopsTogether() {
        val departures = listOf(
            dep("U12", "Dürrlewang", 2, stop = "a"),
            dep("U14", "Vaihingen", 9, stop = "b"),
            dep("56", "Münster", 1, stop = "c"),
            dep("42", "Schlossplatz", 3, stop = "c"),
            dep("S1", "Herrenberg", 30, stop = "d"),
        )
        val order = listOf(
            TileKey("b", LineDirection("U14", "Vaihingen")),
            TileKey("c", null), // all lines at stop c, kept together in line order
            TileKey("a", LineDirection("U12", "Dürrlewang")),
        )
        val tiles = Countdown.tiles(departures, now, limit = 4, order = order)
        // S1 at stop d isn't in the order, so it goes last and falls off the 4-tile limit.
        assertEquals(listOf("U14", "42", "56", "U12"), tiles.map { it.next.line })
    }

    @Test
    fun shortTurningTripsInOneDirectionShareATile() {
        val departures = listOf(
            dep("54", "Max-Eyth-See", 2, stop = "marabu", dir = "H"),
            dep("54", "Freiberg", 9, stop = "marabu", dir = "H"),
            dep("54", "Sommerrain", 4, stop = "marabu", dir = "R"),
            dep("54", "Marabustraße", 12, stop = "marabu", dir = "R"),
        )
        val tiles = Countdown.tiles(departures, now, limit = 6)
        assertEquals(2, tiles.size)
        val outbound = tiles.first { it.next.direction == "H" }
        assertEquals("Max-Eyth-See", outbound.next.destination)
        assertEquals("Freiberg", outbound.after!!.destination)

        // A pick for the whole direction matches every trip going that way.
        val pick = LineDirection("54", "Freiberg / Max-Eyth-See", "H")
        assertEquals(2, departures.count(pick::matches))
        val highlighted = WidgetConfig(emptyList(), highlight = mapOf(TileKey("marabu", pick) to 5))
        assertEquals(5, highlighted.highlightFor(departures[1]))
    }
}
