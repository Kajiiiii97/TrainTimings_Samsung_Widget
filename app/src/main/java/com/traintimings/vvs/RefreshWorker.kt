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
        var failed = false

        // Widgets sharing a stop share one request. The cache keeps every line at the stop and
        // the renderer filters, so changing the chosen lines never needs a new download.
        for ((stopId, entries) in configured.groupBy { it.second.stopId }) {
            val filtered = entries.any { it.second.selections.isNotEmpty() }
            val result = runCatching { VvsApi.departures(stopId, if (filtered) 100 else 60) }
            failed = failed || result.isFailure
            for ((id, _) in entries) {
                val old = WidgetStore.cache(ctx, id)
                val cache = result.fold(
                    onSuccess = { all -> DepartureCache(Instant.now(), all, null) },
                    onFailure = { e ->
                        // First failures with nothing cached yet stay on "Loading…" while we retry.
                        if (old == null && runAttemptCount < MAX_RETRIES) null
                        else DepartureCache(old?.fetchedAt, old?.departures.orEmpty(), e.message ?: "Network error")
                    },
                )
                if (cache != null) WidgetStore.saveCache(ctx, id, cache)
            }
        }

        ids.forEach { WidgetRenderer.render(ctx, mgr, it) }

        val now = Instant.now()
        val next = configured
            .flatMap { (id, config) -> WidgetStore.cache(ctx, id)?.departures.orEmpty().filter(config::matches) }
            .map { it.time }
            .filter { it.isAfter(now) }
            .minOrNull()
        Scheduler.scheduleNext(ctx, next)
        MinuteTicker.schedule(ctx)
        return if (failed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
