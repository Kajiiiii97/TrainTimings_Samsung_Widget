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
 * While the screen is on, [LiveTicker] calls in every 30 seconds:
 * - a quick fetch (just the next few departures) keeps delays and cancellations live and is light
 *   on data; it's merged into the cache, keeping the later trips from the last full fetch;
 * - a full fetch (looking well ahead) runs every 10 minutes, or sooner if the cache is running out,
 *   so quieter lines always have their next trips.
 * The background job, taps and new widgets always do a full fetch.
 */
object Refresher {

    enum class Kind { NONE, QUICK, FULL }

    private val QUICK_EVERY: Duration = Duration.ofSeconds(25)
    private val FULL_EVERY: Duration = Duration.ofMinutes(10)
    /** Full refresh early if a stop's cached departures run out within this window. */
    private val HORIZON: Duration = Duration.ofMinutes(20)
    /** Don't start fetches closer together than this. */
    private val MIN_GAP: Duration = Duration.ofSeconds(20)

    private const val QUICK_LIMIT = 20
    private const val FULL_LIMIT_PICKED_LINES = 150
    private const val FULL_LIMIT_ALL_LINES = 60

    private val lock = Any()

    /** What the live tick should fetch now, if anything. */
    fun due(ctx: Context): Kind {
        val stopIds = TrainWidgetProvider.allIds(ctx)
            .flatMap { WidgetStore.config(ctx, it)?.stops.orEmpty() }
            .map { it.stopId }
            .toSet()
        if (stopIds.isEmpty()) return Kind.NONE
        val now = Instant.now()
        if (Duration.between(WidgetStore.lastRefreshAttempt(ctx), now) < MIN_GAP) return Kind.NONE
        return stopIds.map { due(WidgetStore.stopCache(ctx, it), now) }.maxByOrNull { it.ordinal } ?: Kind.NONE
    }

    internal fun due(cache: DepartureCache?, now: Instant): Kind {
        if (cache == null) return Kind.FULL
        val fetchedAt = cache.fetchedAt ?: return Kind.FULL
        val fullAt = cache.fullAt ?: return Kind.FULL
        val lastTrip = cache.departures.maxOfOrNull { it.time } ?: return Kind.FULL
        return when {
            Duration.between(fullAt, now) > FULL_EVERY -> Kind.FULL
            lastTrip.isBefore(now.plus(HORIZON)) -> Kind.FULL
            Duration.between(fetchedAt, now) > QUICK_EVERY -> Kind.QUICK
            else -> Kind.NONE
        }
    }

    /**
     * Folds a quick fetch into the cached list: the quick fetch is the truth up to its last planned
     * departure (so delays, cancellations and dropped trips update), later cached trips are kept.
     */
    internal fun merge(cached: List<Departure>, quick: List<Departure>): List<Departure> {
        val cutoff = quick.maxOfOrNull { it.planned } ?: return cached
        return (quick + cached.filter { it.planned.isAfter(cutoff) }).sortedBy { it.time }
    }

    /**
     * Fetches all stops (in parallel), saves, redraws every widget and reschedules.
     * With [keepLoadingOnFailure] a stop that has never loaded stays on "Loading…" instead of an error.
     * Returns true if any stop failed.
     */
    fun refreshAll(ctx: Context, keepLoadingOnFailure: Boolean, kind: Kind = Kind.FULL): Boolean = synchronized(lock) {
        WidgetStore.markRefreshAttempt(ctx, Instant.now())
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = TrainWidgetProvider.allIds(ctx).toList()
        val selections = ids.flatMap { WidgetStore.config(ctx, it)?.stops.orEmpty() }
        val byStop = selections.groupBy { it.stopId }
        var failed = false

        if (byStop.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(byStop.size.coerceAtMost(4))
            try {
                // One request per stop, however many widgets use it.
                val jobs = byStop.map { (stopId, uses) ->
                    val old = WidgetStore.stopCache(ctx, stopId)
                    val full = kind == Kind.FULL || old?.fullAt == null
                    // A stop where only some lines were picked looks further ahead on full fetches
                    // so quieter lines don't run out between them.
                    val limit = when {
                        !full -> QUICK_LIMIT
                        uses.all { it.lines.isNotEmpty() } -> FULL_LIMIT_PICKED_LINES
                        else -> FULL_LIMIT_ALL_LINES
                    }
                    Job(stopId, old, full, pool.submit<List<Departure>> { VvsApi.departures(stopId, limit) })
                }
                for (job in jobs) {
                    val result = runCatching { job.future.get(25, TimeUnit.SECONDS) }
                    failed = failed || result.isFailure
                    val old = job.old
                    val now = Instant.now()
                    val cache = result.fold(
                        onSuccess = { fresh ->
                            if (job.full) DepartureCache(now, fresh, null, fullAt = now)
                            else DepartureCache(now, merge(old?.departures.orEmpty(), fresh), null, fullAt = old?.fullAt)
                        },
                        onFailure = { e ->
                            if (old == null && keepLoadingOnFailure) null
                            else DepartureCache(old?.fetchedAt, old?.departures.orEmpty(), reason(e), old?.fullAt)
                        },
                    )
                    if (cache != null) WidgetStore.saveStopCache(ctx, job.stopId, cache)
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
        LiveTicker.schedule(ctx)
        failed
    }

    /** A short, readable reason for the widget instead of a raw exception message. */
    internal fun reason(e: Throwable): String {
        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
        return when (cause) {
            is java.net.UnknownHostException -> "no internet"
            is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> "timed out"
            is java.net.ConnectException -> "connection refused"
            is java.io.IOException -> cause.message?.take(40) ?: "network error"
            is org.json.JSONException -> "unexpected reply from VVS"
            else -> cause.javaClass.simpleName
        }
    }

    private class Job(
        val stopId: String,
        val old: DepartureCache?,
        val full: Boolean,
        val future: java.util.concurrent.Future<List<Departure>>,
    )
}
