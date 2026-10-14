package com.traintimings.vvs

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

data class Stop(val id: String, val name: String)

/**
 * A line heading one way, e.g. S1 -> Herrenberg. This is what the user picks.
 *
 * When VVS tells us the trip direction ([dir] = "H" outbound / "R" return), the pick covers the
 * whole direction, so short-turning trips (54 to Max-Eyth-See or on to Freiberg) count as one.
 * Then [destination] lists the destinations seen, e.g. "Freiberg / Max-Eyth-See".
 * Without a direction code the pick is just that one destination.
 */
data class LineDirection(val line: String, val destination: String, val dir: String = "") {
    val label: String get() = "$line  →  $destination"

    val destinations: List<String> get() = destination.split(DEST_SEPARATOR)

    fun matches(d: Departure): Boolean =
        d.line == line && if (dir.isNotEmpty()) d.direction == dir else d.destination == destination

    /** Whether this pick includes [other], e.g. a whole direction includes one of its destinations. */
    fun covers(other: LineDirection): Boolean = line == other.line && when {
        dir.isNotEmpty() && other.dir.isNotEmpty() -> dir == other.dir
        dir.isNotEmpty() -> other.destination in destinations
        else -> other.dir.isEmpty() && destination == other.destination
    }

    fun toJson(): JSONObject = JSONObject().put("line", line).put("destination", destination).apply {
        if (dir.isNotEmpty()) put("dir", dir)
    }

    companion object {
        const val DEST_SEPARATOR = " / "

        fun fromJson(o: JSONObject) =
            LineDirection(o.getString("line"), o.getString("destination"), o.optString("dir", ""))
    }
}

data class Departure(
    val line: String,
    val destination: String,
    val productClass: Int,
    val planned: Instant,
    val estimated: Instant?,
    val cancelled: Boolean,
    val platform: String,
    /** The stop this departure was fetched for, so the same line at two stops stays two tiles. */
    val stopId: String = "",
    /** Trip direction from VVS: "H" (outbound) or "R" (return), or empty when unknown. */
    val direction: String = "",
) {
    val time: Instant get() = estimated ?: planned
    val delayMinutes: Long get() = estimated?.let { Duration.between(planned, it).toMinutes() } ?: 0
    val lineDirection: LineDirection get() = LineDirection(line, destination, direction)

    /** Departures with the same key share a tile: same line and direction (or destination when unknown). */
    val groupKey: String get() = if (direction.isNotEmpty()) "$line|dir:$direction" else "$line|$destination"

    fun toJson(): JSONObject = JSONObject()
        .put("line", line)
        .put("destination", destination)
        .put("productClass", productClass)
        .put("planned", planned.toEpochMilli())
        .put("estimated", estimated?.toEpochMilli() ?: -1L)
        .put("cancelled", cancelled)
        .put("platform", platform)
        .put("stopId", stopId)
        .put("dir", direction)

    companion object {
        fun fromJson(o: JSONObject): Departure {
            val est = o.optLong("estimated", -1L)
            return Departure(
                line = o.getString("line"),
                destination = o.getString("destination"),
                productClass = o.optInt("productClass", -1),
                planned = Instant.ofEpochMilli(o.getLong("planned")),
                estimated = if (est >= 0) Instant.ofEpochMilli(est) else null,
                cancelled = o.optBoolean("cancelled", false),
                platform = o.optString("platform", ""),
                stopId = o.optString("stopId", ""),
                direction = o.optString("dir", ""),
            )
        }
    }
}

/** One stop in a widget and the lines picked there. An empty [lines] list means "every line at this stop". */
data class StopSelection(val stopId: String, val stopName: String, val lines: List<LineDirection>) {
    fun matches(d: Departure): Boolean = lines.isEmpty() || lines.any { it.matches(d) }

    fun toJson(): JSONObject = JSONObject()
        .put("stopId", stopId)
        .put("stopName", stopName)
        .put("selections", JSONArray().apply { lines.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): StopSelection {
            val arr = o.optJSONArray("selections") ?: JSONArray()
            return StopSelection(
                stopId = o.getString("stopId"),
                stopName = o.getString("stopName"),
                lines = (0 until arr.length()).map { LineDirection.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

/**
 * One row in the widget's tile order: a line + direction at a stop, or a whole stop when
 * [line] is null ("all lines").
 */
data class TileKey(val stopId: String, val line: LineDirection?) {
    fun encode(): String = "$stopId|${line?.line ?: "*"}|${line?.destination ?: "*"}|${line?.dir.orEmpty()}"

    /** Whether this row's tile is the one [d] belongs to. */
    fun matches(d: Departure): Boolean = stopId == d.stopId && (line == null || line.matches(d))

    companion object {
        fun decode(s: String): TileKey? {
            val parts = s.split("|")
            // Older versions wrote three parts (no direction code).
            if (parts.size != 3 && parts.size != 4) return null
            val line = if (parts[1] == "*" && parts[2] == "*") null
            else LineDirection(parts[1], parts[2], parts.getOrElse(3) { "" })
            return TileKey(parts[0], line)
        }
    }
}

/**
 * What a single widget instance shows: lines from one or more stops.
 * [order] is the user's tile order; when empty, tiles fall back to the soonest-first default.
 * [highlight] is, per row, how many minutes before departure the tile lights up ([HIGHLIGHT_OFF] = never).
 */
data class WidgetConfig(
    val stops: List<StopSelection>,
    val order: List<TileKey> = emptyList(),
    val highlight: Map<TileKey, Int> = emptyMap(),
) {

    /** Highlight threshold for a tile: its own row's setting, else its stop's "all lines" row, else the default. */
    fun highlightFor(d: Departure): Int =
        highlight.entries.firstOrNull { it.key.line != null && it.key.matches(d) }?.value
            ?: highlight[TileKey(d.stopId, null)]
            ?: DEFAULT_HIGHLIGHT

    fun toJson(): JSONObject = JSONObject()
        .put("stops", JSONArray().apply { stops.forEach { put(it.toJson()) } })
        .put("order", JSONArray().apply { order.forEach { put(it.encode()) } })
        .put("highlight", JSONObject().apply { highlight.forEach { (k, v) -> put(k.encode(), v) } })

    companion object {
        fun fromJson(o: JSONObject): WidgetConfig {
            val arr = o.optJSONArray("stops")
                // Widgets set up before multi-stop support stored a single stop at the top level.
                ?: return WidgetConfig(listOf(StopSelection.fromJson(o)))
            val order = o.optJSONArray("order")
            val highlight = o.optJSONObject("highlight")
            return WidgetConfig(
                stops = (0 until arr.length()).map { StopSelection.fromJson(arr.getJSONObject(it)) },
                order = if (order == null) emptyList()
                else (0 until order.length()).mapNotNull { TileKey.decode(order.optString(it)) },
                highlight = highlight?.keys()?.asSequence()
                    ?.mapNotNull { k -> TileKey.decode(k)?.let { it to highlight.optInt(k, DEFAULT_HIGHLIGHT) } }
                    ?.toMap()
                    .orEmpty(),
            )
        }

        /** Light up a tile when its train leaves within this many minutes. */
        const val DEFAULT_HIGHLIGHT = 1
        const val HIGHLIGHT_OFF = -1
    }
}

/** Last fetched departures for a stop, kept so widgets can roll forward while offline. */
data class DepartureCache(val fetchedAt: Instant?, val departures: List<Departure>, val error: String?) {
    fun toJson(): JSONObject = JSONObject()
        .put("fetchedAt", fetchedAt?.toEpochMilli() ?: -1L)
        .put("error", error ?: "")
        .put("departures", JSONArray().apply { departures.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): DepartureCache {
            val arr = o.optJSONArray("departures") ?: JSONArray()
            val fetched = o.optLong("fetchedAt", -1L)
            return DepartureCache(
                fetchedAt = if (fetched >= 0) Instant.ofEpochMilli(fetched) else null,
                departures = (0 until arr.length()).map { Departure.fromJson(arr.getJSONObject(it)) },
                error = o.optString("error", "").ifBlank { null },
            )
        }
    }
}
