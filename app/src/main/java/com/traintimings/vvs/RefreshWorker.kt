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
        val selections = ids.flatMap { WidgetStore.config(ctx, it)?.stops.orEmpty() }
        var failed = false

        // One request per stop, however many widgets use it.
        for ((stopId, uses) in selections.groupBy { it.stopId }) {
            val filtered = uses.all { it.lines.isNotEmpty() }
            val result = runCatching { VvsApi.departures(stopId, if (filtered) 100 else 60) }
            failed = failed || result.isFailure
            val old = WidgetStore.stopCache(ctx, stopId)
            val cache = result.fold(
                onSuccess = { all -> DepartureCache(Instant.now(), all, null) },
                onFailure = { e ->
                    // First failures with nothing cached yet stay on "Loading…" while we retry.
                    if (old == null && runAttemptCount < MAX_RETRIES) null
                    else DepartureCache(old?.fetchedAt, old?.departures.orEmpty(), e.message ?: "Network error")
                },
            )
            if (cache != null) WidgetStore.saveStopCache(ctx, stopId, cache)
        }
        WidgetStore.pruneStops(ctx, selections.map { it.stopId }.toSet())

        ids.forEach { WidgetRenderer.render(ctx, mgr, it) }

        val now = Instant.now()
        val next = selections
            .flatMap { sel -> WidgetStore.stopCache(ctx, sel.stopId)?.departures.orEmpty().filter(sel::matches) }
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
