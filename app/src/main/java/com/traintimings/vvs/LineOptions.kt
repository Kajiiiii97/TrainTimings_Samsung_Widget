package com.traintimings.vvs

/** Builds the tickable line + direction rows for a stop on the setup screen. */
object LineOptions {

    /**
     * One row per line and direction seen in [departures]. When VVS gives a direction code, all
     * destinations going that way (e.g. a bus that sometimes short-turns) share one row.
     * Saved picks ([preselected]) that aren't running right now stay listed so they can be kept.
     * Returns the rows in line order, each with the picks it replaces (to tick it and carry settings over).
     */
    fun build(departures: List<Departure>, preselected: Collection<LineDirection>): List<Option> {
        val rows = departures.groupBy { it.groupKey }.values.map { group ->
            val first = group.first()
            val destinations = group.map { it.destination }.distinct().sorted()
            first.productClass to LineDirection(
                first.line,
                destinations.joinToString(LineDirection.DEST_SEPARATOR),
                first.direction,
            )
        }

        val options = rows.map { (pc, row) ->
            val replaces = preselected.filter { row.covers(it) }
            // Keep destinations from earlier picks in the label even if they don't run right now.
            val label = if (row.dir.isEmpty()) row else row.copy(
                destination = (row.destinations + replaces.flatMap { it.destinations })
                    .distinct().sorted().joinToString(LineDirection.DEST_SEPARATOR),
            )
            Option(label, pc, replaces)
        }.toMutableList()
        preselected.filter { pre -> options.none { pre in it.replaces } }
            .forEach { options += Option(it, -1, listOf(it)) }

        return options.sortedWith(LineOrder.comparator({ it.line }, { it.productClass }))
    }

    data class Option(val line: LineDirection, val productClass: Int, val replaces: List<LineDirection>) {
        val checked: Boolean get() = replaces.isNotEmpty()
    }
}
