package com.traintimings.vvs

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Instant

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = TrainWidgetProvider.allIds(ctx).toList()
        val configured = ids.mapNotNull { id -> WidgetStore.config(ctx, id)?.let { id to it } }

        // Widgets sharing a stop share one request.
        for ((stopId, entries) in configured.groupBy { it.second.stopId }) {
            val filtered = entries.any { it.second.selections.isNotEmpty() }
            val result = runCatching { VvsApi.departures(stopId, if (filtered) 80 else 60) }
            for ((id, config) in entries) {
                val cache = result.fold(
                    onSuccess = { all -> DepartureCache(Instant.now(), all.filter(config::matches), null) },
                    onFailure = { e ->
                        val old = WidgetStore.cache(ctx, id)
                        DepartureCache(old?.fetchedAt, old?.departures.orEmpty(), e.message ?: "Network error")
                    },
                )
                WidgetStore.saveCache(ctx, id, cache)
            }
        }

        ids.forEach { WidgetRenderer.render(ctx, mgr, it) }

        val now = Instant.now()
        val next = configured
            .flatMap { (id, _) -> WidgetStore.cache(ctx, id)?.departures.orEmpty() }
            .map { it.time }
            .filter { it.isAfter(now) }
            .minOrNull()
        Scheduler.scheduleNext(ctx, next)
        MinuteTicker.schedule(ctx)
        return Result.success()
    }
}
