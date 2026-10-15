package com.traintimings.vvs

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject

class TrainWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetRenderer.render(ctx, mgr, it) }
        Scheduler.ensurePeriodic(ctx)
        Scheduler.refreshNow(ctx)
        MinuteTicker.schedule(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        // Resized: re-render so the number of rows matches the new height.
        WidgetRenderer.render(ctx, mgr, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { WidgetStore.remove(ctx, it) }
    }

    override fun onDisabled(ctx: Context) {
        Scheduler.cancelAll(ctx)
        MinuteTicker.cancel(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetRenderer.render(ctx, AppWidgetManager.getInstance(ctx), id, ctx.getString(R.string.widget_refreshing))
                }
                refreshInBackground(ctx)
            }
            ACTION_TICK -> {
                val ids = allIds(ctx)
                if (ids.isEmpty()) return
                val mgr = AppWidgetManager.getInstance(ctx)
                ids.forEach { WidgetRenderer.render(ctx, mgr, it) }
                MinuteTicker.schedule(ctx)
                // The minute tick keeps running even when background jobs are held back, so it
                // fetches fresh data itself once the cache gets old or is about to run out.
                if (Refresher.needsRefresh(ctx)) refreshInBackground(ctx)
            }
            ACTION_PINNED -> {
                // Widget added from inside the app via requestPinAppWidget: apply the config chosen there.
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val json = intent.getStringExtra(EXTRA_CONFIG)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID && json != null) {
                    WidgetStore.saveConfig(ctx, id, WidgetConfig.fromJson(JSONObject(json)))
                    WidgetRenderer.render(ctx, AppWidgetManager.getInstance(ctx), id)
                    Scheduler.ensurePeriodic(ctx)
                    MinuteTicker.schedule(ctx)
                    refreshInBackground(ctx)
                }
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    /** Fetch right away on a background thread, keeping this receiver alive until it's done. */
    private fun refreshInBackground(ctx: Context) {
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // If the direct fetch fails, leave a job queued to retry once there's network.
                if (Refresher.refreshAll(app, keepLoadingOnFailure = false)) Scheduler.refreshNow(app)
            } catch (e: Exception) {
                Scheduler.refreshNow(app)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "com.traintimings.vvs.REFRESH"
        const val ACTION_TICK = "com.traintimings.vvs.TICK"
        const val ACTION_PINNED = "com.traintimings.vvs.PINNED"
        const val EXTRA_CONFIG = "config"

        fun allIds(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, TrainWidgetProvider::class.java))
    }
}
