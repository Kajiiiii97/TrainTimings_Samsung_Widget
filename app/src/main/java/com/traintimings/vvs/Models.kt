package com.traintimings.vvs

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

data class Stop(val id: String, val name: String)

/** A line heading towards a destination, e.g. S1 -> Herrenberg. This is what the user picks. */
data class LineDirection(val line: String, val destination: String) {
    val label: String get() = "$line  →  $destination"

    fun toJson(): JSONObject = JSONObject().put("line", line).put("destination", destination)

    companion object {
        fun fromJson(o: JSONObject) = LineDirection(o.getString("line"), o.getString("destination"))
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
) {
    val time: Instant get() = estimated ?: planned
    val delayMinutes: Long get() = estimated?.let { Duration.between(planned, it).toMinutes() } ?: 0
    val lineDirection: LineDirection get() = LineDirection(line, destination)

    fun toJson(): JSONObject = JSONObject()
        .put("line", line)
        .put("destination", destination)
        .put("productClass", productClass)
        .put("planned", planned.toEpochMilli())
        .put("estimated", estimated?.toEpochMilli() ?: -1L)
        .put("cancelled", cancelled)
        .put("platform", platform)
        .put("stopId", stopId)

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
            )
        }
    }
}

/** One stop in a widget and the lines picked there. An empty [lines] list means "every line at this stop". */
data class StopSelection(val stopId: String, val stopName: String, val lines: List<LineDirection>) {
    fun matches(d: Departure): Boolean = lines.isEmpty() || d.lineDirection in lines

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
    fun encode(): String = "$stopId|${line?.line ?: "*"}|${line?.destination ?: "*"}"

    companion object {
        fun decode(s: String): TileKey? {
            val parts = s.split("|")
            if (parts.size != 3) return null
            val line = if (parts[1] == "*" && parts[2] == "*") null else LineDirection(parts[1], parts[2])
            return TileKey(parts[0], line)
        }
    }
}

/**
 * What a single widget instance shows: lines from one or more stops.
 * [order] is the user's tile order; when empty, tiles fall back to the soonest-first default.
 */
data class WidgetConfig(val stops: List<StopSelection>, val order: List<TileKey> = emptyList()) {

    fun toJson(): JSONObject = JSONObject()
        .put("stops", JSONArray().apply { stops.forEach { put(it.toJson()) } })
        .put("order", JSONArray().apply { order.forEach { put(it.encode()) } })

    companion object {
        fun fromJson(o: JSONObject): WidgetConfig {
            val arr = o.optJSONArray("stops")
                // Widgets set up before multi-stop support stored a single stop at the top level.
                ?: return WidgetConfig(listOf(StopSelection.fromJson(o)))
            val order = o.optJSONArray("order")
            return WidgetConfig(
                stops = (0 until arr.length()).map { StopSelection.fromJson(arr.getJSONObject(it)) },
                order = if (order == null) emptyList()
                else (0 until order.length()).mapNotNull { TileKey.decode(order.optString(it)) },
            )
        }
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
