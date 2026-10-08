package com.traintimings.vvs

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

object VvsParser {

    fun parseStops(body: String): List<Stop> {
        val locations = JSONObject(body).optJSONArray("locations") ?: return emptyList()
        return locations.objects()
            .filter { str(it, "type") == "stop" }
            .sortedByDescending { it.optInt("matchQuality", 0) }
            .mapNotNull { loc ->
                val id = str(loc, "id")
                val name = str(loc, "name").ifBlank { str(loc, "disassembledName") }
                if (id.isBlank() || name.isBlank()) null else Stop(id, name)
            }
            .distinctBy { it.id }
    }

    fun parseDepartures(body: String): List<Departure> {
        val events = JSONObject(body).optJSONArray("stopEvents") ?: return emptyList()
        return events.objects().mapNotNull { ev ->
            val t = ev.optJSONObject("transportation") ?: return@mapNotNull null
            val line = str(t, "disassembledName")
                .ifBlank { str(t, "number") }
                .ifBlank { str(t, "name") }
            val destination = t.optJSONObject("destination")?.let { str(it, "name") }.orEmpty()
            val planned = parseTime(str(ev, "departureTimePlanned")) ?: return@mapNotNull null
            if (line.isBlank()) return@mapNotNull null

            val status = ev.optJSONArray("realtimeStatus")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }.orEmpty()
            val props = ev.optJSONObject("location")?.optJSONObject("properties")

            Departure(
                line = line,
                destination = destination.ifBlank { "?" },
                productClass = t.optJSONObject("product")?.optInt("class", -1) ?: -1,
                planned = planned,
                estimated = parseTime(str(ev, "departureTimeEstimated")),
                cancelled = ev.optBoolean("isCancelled", false) || "TRIP_CANCELLED" in status,
                platform = props?.let { str(it, "platform").ifBlank { str(it, "platformName") } }.orEmpty(),
            )
        }.sortedBy { it.time }
    }

    /** The departure monitor echoes the resolved stop under "locations". */
    fun parseStopName(body: String): String? {
        val loc = JSONObject(body).optJSONArray("locations")?.optJSONObject(0) ?: return null
        return str(loc, "name").ifBlank { str(loc, "disassembledName") }.ifBlank { null }
    }

    private fun parseTime(s: String): Instant? {
        if (s.isBlank()) return null
        return runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
    }

    /** optString turns JSON null into the text "null", so guard against that. */
    private fun str(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.optString(key, "").trim()

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
}
