package com.traintimings.vvs

import android.content.Context
import org.json.JSONObject

/** Per-widget config and cached departures, stored as JSON in SharedPreferences. */
object WidgetStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun config(ctx: Context, id: Int): WidgetConfig? =
        prefs(ctx).getString("cfg_$id", null)?.let { runCatching { WidgetConfig.fromJson(JSONObject(it)) }.getOrNull() }

    fun saveConfig(ctx: Context, id: Int, config: WidgetConfig) {
        prefs(ctx).edit()
            .putString("cfg_$id", config.toJson().toString())
            .remove("cache_$id")
            .apply()
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
