package com.traintimings.vvs

import android.content.Context
import org.json.JSONObject

/** Per-widget config and cached departures, stored as JSON in SharedPreferences. */
object WidgetStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun config(ctx: Context, id: Int): WidgetConfig? =
        prefs(ctx).getString("cfg_$id", null)?.let { runCatching { WidgetConfig.fromJson(JSONObject(it)) }.getOrNull() }

    /**
     * Saves a widget's config. Cached departures are unfiltered, so they stay valid when only the
     * chosen lines change; [fresh] (e.g. what the setup screen just downloaded) replaces them.
     */
    fun saveConfig(ctx: Context, id: Int, config: WidgetConfig, fresh: DepartureCache? = null) {
        val sameStop = config(ctx, id)?.stopId == config.stopId
        val edit = prefs(ctx).edit().putString("cfg_$id", config.toJson().toString())
        when {
            fresh != null -> edit.putString("cache_$id", fresh.toJson().toString())
            !sameStop -> edit.remove("cache_$id")
        }
        edit.apply()
    }

    fun cache(ctx: Context, id: Int): DepartureCache? =
        prefs(ctx).getString("cache_$id", null)?.let { runCatching { DepartureCache.fromJson(JSONObject(it)) }.getOrNull() }

    fun saveCache(ctx: Context, id: Int, cache: DepartureCache) {
        prefs(ctx).edit().putString("cache_$id", cache.toJson().toString()).apply()
    }

    fun remove(ctx: Context, id: Int) {
        prefs(ctx).edit().remove("cfg_$id").remove("cache_$id").apply()
    }
}
