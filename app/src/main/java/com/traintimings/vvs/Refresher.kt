package com.traintimings.vvs

import android.appwidget.AppWidgetManager
import android.content.Context
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads fresh departures for every stop any widget uses, then redraws and reschedules.
 *
 * Called from the background job and, more importantly, straight from the minute tick and from
 * taps: Samsung's battery manager often holds background jobs back for a long time, and without
 * fresh data the widget slowly empties as each cached trip leaves.
 */
object Refresher {
    /** Data older than this is refreshed on the next minute tick (only ticks while the screen is on). */
    private val STALE_AFTER: Duration = Duration.ofMinutes(4)
    /** Don't start fetches closer together than this. */
    private val MIN_GAP: Duration = Duration.ofSeconds(50)
    /** Refresh early if a stop's cached departures run out within this window. */
    private val HORIZON: Duration = Duration.ofMinutes(20)

    private val lock = Any()

    /** Whether the cached data is old or running out, so the minute tick should fetch. */
    fun needsRefresh(ctx: Context): Boolean {
        val stopIds = TrainWidgetProvider.allIds(ctx)
            .flatMap { WidgetStore.config(ctx, it)?.stops.orEmpty() }
            .map { it.stopId }
            .toSet()
        if (stopIds.isEmpty()) return false
        val now = Instant.now()
        if (Duration.between(WidgetStore.lastRefreshAttempt(ctx), now) < MIN_GAP) return false
        return stopIds.any { stopId ->
            val cache = WidgetStore.stopCache(ctx, stopId)
            val fetchedAt = cache?.fetchedAt
            when {
                cache == null || fetchedAt == null -> true
                Duration.between(fetchedAt, now) > STALE_AFTER -> true
                else -> cache.departures.maxOfOrNull { it.time }?.isBefore(now.plus(HORIZON)) ?: true
            }
        }
    }

    /**
     * Fetches all stops (in parallel), saves, redraws every widget and reschedules.
     * With [keepLoadingOnFailure] a stop that has never loaded stays on "Loading…" instead of an error.
     * Returns true if any stop failed.
     */
    fun refreshAll(ctx: Context, keepLoadingOnFailure: Boolean): Boolean = synchronized(lock) {
        WidgetStore.markRefreshAttempt(ctx, Instant.now())
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = TrainWidgetProvider.allIds(ctx).toList()
        val selections = ids.flatMap { WidgetStore.config(ctx, it)?.stops.orEmpty() }
        val byStop = selections.groupBy { it.stopId }
        var failed = false

        if (byStop.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(byStop.size.coerceAtMost(4))
            try {
                // One request per stop, however many widgets use it. A stop where only some lines
                // were picked fetches further ahead so quieter lines don't run out between refreshes.
                val results = byStop.mapValues { (stopId, uses) ->
                    val limit = if (uses.all { it.lines.isNotEmpty() }) 150 else 60
                    pool.submit<List<Departure>> { VvsApi.departures(stopId, limit) }
                }
                for ((stopId, future) in results) {
                    val result = runCatching { future.get(25, TimeUnit.SECONDS) }
                    failed = failed || result.isFailure
                    val old = WidgetStore.stopCache(ctx, stopId)
                    val cache = result.fold(
                        onSuccess = { all -> DepartureCache(Instant.now(), all, null) },
                        onFailure = { e ->
                            if (old == null && keepLoadingOnFailure) null
                            else DepartureCache(old?.fetchedAt, old?.departures.orEmpty(), e.message ?: "Network error")
                        },
                    )
                    if (cache != null) WidgetStore.saveStopCache(ctx, stopId, cache)
                }
            } finally {
                pool.shutdownNow()
            }
        }
        WidgetStore.pruneStops(ctx, byStop.keys)

        ids.forEach { WidgetRenderer.render(ctx, mgr, it) }

        val now = Instant.now()
        val next = selections
            .flatMap { sel -> WidgetStore.stopCache(ctx, sel.stopId)?.departures.orEmpty().filter(sel::matches) }
            .map { it.time }
            .filter { it.isAfter(now) }
            .minOrNull()
        Scheduler.scheduleNext(ctx, next)
        MinuteTicker.schedule(ctx)
        failed
    }
}
