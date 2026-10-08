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
            )
        }
    }
}

/** What a single widget instance shows. An empty [selections] list means "every line at this stop". */
data class WidgetConfig(val stopId: String, val stopName: String, val selections: List<LineDirection>) {
    fun matches(d: Departure): Boolean = selections.isEmpty() || d.lineDirection in selections

    fun toJson(): JSONObject = JSONObject()
        .put("stopId", stopId)
        .put("stopName", stopName)
        .put("selections", JSONArray().apply { selections.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): WidgetConfig {
            val arr = o.optJSONArray("selections") ?: JSONArray()
            return WidgetConfig(
                stopId = o.getString("stopId"),
                stopName = o.getString("stopName"),
                selections = (0 until arr.length()).map { LineDirection.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

/** Last fetched departures for a widget, kept so the widget can roll forward while offline. */
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
