package com.traintimings.vvs

/** Stable, human ordering of lines: S-Bahn, Stadtbahn, regional trains, other rail, buses; then by number. */
object LineOrder {

    fun rank(line: String, productClass: Int): Int {
        val l = line.uppercase()
        return when {
            l.startsWith("S") && l.getOrNull(1)?.isDigit() == true -> 0
            l.startsWith("U") && l.getOrNull(1)?.isDigit() == true -> 1
            l.startsWith("R") || l.startsWith("MEX") || l.startsWith("IRE") || l.startsWith("IC") -> 2
            productClass in 0..4 -> 3
            else -> 4
        }
    }

    private fun number(line: String): Int = line.filter { it.isDigit() }.take(4).toIntOrNull() ?: Int.MAX_VALUE

    fun <T> comparator(lineDirection: (T) -> LineDirection, productClass: (T) -> Int): Comparator<T> =
        compareBy<T>(
            { rank(lineDirection(it).line, productClass(it)) },
            { number(lineDirection(it).line) },
            { lineDirection(it).line },
            { lineDirection(it).destination },
        )
}
