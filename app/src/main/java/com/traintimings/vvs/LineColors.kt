package com.traintimings.vvs

import android.graphics.Color

/** Approximate VVS line colours so the badges look familiar at a glance. */
object LineColors {
    private val sBahn = mapOf(
        "S1" to "#5FAF2F",
        "S2" to "#E2001A",
        "S3" to "#F39200",
        "S4" to "#0069B4",
        "S5" to "#00A5DB",
        "S6" to "#7B4B23",
        "S60" to "#8C8C2E",
        "S62" to "#CC8A2E",
    )

    private val stadtbahnPalette = listOf(
        "#C1854A", "#F18A00", "#9A6A4A", "#8DC63F", "#4FA0D8", "#E5007D",
        "#00A88E", "#B5A96C", "#8E3B97", "#0A6FB6", "#F7A600", "#D2A8C9",
    )

    private val regional = Regex("^(RE|RB|MEX|IRE|IC|ICE|EC|R)\\d*.*")

    fun background(line: String, productClass: Int): Int {
        val l = line.uppercase().replace(" ", "")
        val hex = when {
            sBahn.containsKey(l) -> sBahn.getValue(l)
            l.startsWith("S") && l.drop(1).firstOrNull()?.isDigit() == true -> "#408A2C"
            l.startsWith("U") && l.drop(1).firstOrNull()?.isDigit() == true ->
                stadtbahnPalette[(l.drop(1).filter { it.isDigit() }.toIntOrNull() ?: 0) % stadtbahnPalette.size]
            regional.matches(l) -> "#EC0016"
            l.startsWith("N") -> "#1B2A6B"
            productClass in 0..4 -> "#6B4E9B" // other rail, e.g. Zahnradbahn
            else -> "#5A6770" // buses
        }
        return Color.parseColor(hex)
    }
}
