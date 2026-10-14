package com.traintimings.vvs

import android.content.Context
import org.json.JSONObject

/**
 * Per-widget config and per-stop cached departures, stored as JSON in SharedPreferences.
 * Departures are cached per stop and unfiltered, so widgets sharing a stop share one download and
 * changing the chosen lines never needs a new one.
 */
object WidgetStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun config(ctx: Context, id: Int): WidgetConfig? =
        prefs(ctx).getString("cfg_$id", null)?.let { runCatching { WidgetConfig.fromJson(JSONObject(it)) }.getOrNull() }

    fun saveConfig(ctx: Context, id: Int, config: WidgetConfig) {
        prefs(ctx).edit().putString("cfg_$id", config.toJson().toString()).remove("cache_$id").apply()
    }

    fun stopCache(ctx: Context, stopId: String): DepartureCache? =
        prefs(ctx).getString("stop_$stopId", null)
            ?.let { runCatching { DepartureCache.fromJson(JSONObject(it)) }.getOrNull() }
            ?.let { c -> c.copy(departures = c.departures.map { it.copy(stopId = stopId) }) }

    fun saveStopCache(ctx: Context, stopId: String, cache: DepartureCache) {
        prefs(ctx).edit().putString("stop_$stopId", cache.toJson().toString()).apply()
    }

    fun remove(ctx: Context, id: Int) {
        prefs(ctx).edit().remove("cfg_$id").remove("cache_$id").apply()
    }

    /** Drops cached stops no widget uses any more. */
    fun pruneStops(ctx: Context, inUse: Set<String>) {
        val p = prefs(ctx)
        val stale = p.all.keys.filter { it.startsWith("stop_") && it.removePrefix("stop_") !in inUse }
        if (stale.isNotEmpty()) p.edit().apply { stale.forEach { remove(it) } }.apply()
    }
}
