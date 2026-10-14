package com.traintimings.vvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LineOptionsTest {

    private val now = Instant.parse("2026-10-08T12:00:00Z")

    private fun dep(line: String, dest: String, dir: String) = Departure(
        line = line,
        destination = dest,
        productClass = 5,
        planned = now,
        estimated = null,
        cancelled = false,
        platform = "",
        direction = dir,
    )

    @Test
    fun mergesDestinationsByDirectionAndCarriesOldPicksOver() {
        val departures = listOf(
            dep("54", "Max-Eyth-See", "H"),
            dep("54", "Freiberg", "H"),
            dep("54", "Sommerrain", "R"),
            dep("42", "Schlossplatz", ""),
        )
        // Picked before direction merging existed: one destination of the outbound direction.
        val oldPick = LineDirection("54", "Freiberg")
        val options = LineOptions.build(departures, listOf(oldPick))

        assertEquals(
            listOf("42  →  Schlossplatz", "54  →  Freiberg / Max-Eyth-See", "54  →  Sommerrain"),
            options.map { it.line.label },
        )
        val outbound = options.first { it.line.dir == "H" }
        assertTrue(outbound.checked)
        assertEquals(listOf(oldPick), outbound.replaces)
    }

    @Test
    fun keepsSavedPicksThatAreNotRunningRightNow() {
        val saved = LineDirection("54", "Freiberg / Max-Eyth-See", "H")
        val options = LineOptions.build(listOf(dep("54", "Sommerrain", "R")), listOf(saved))
        assertEquals(2, options.size)
        assertTrue(options.first { it.line == saved }.checked)
    }
}
