package com.traintimings.vvs

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "vvs-periodic"
    private const val NOW = "vvs-now"
    private const val NEXT = "vvs-next"

    /** Background refresh every 15 minutes (the shortest interval Android allows) to pick up delays. */
    fun ensurePeriodic(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>().build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Wake up just after the next train leaves so the widget rolls forward instead of showing a gone train. */
    fun scheduleNext(ctx: Context, nextDeparture: Instant?) {
        if (nextDeparture == null) return
        val seconds = (Duration.between(Instant.now(), nextDeparture).seconds + 20)
            .coerceIn(60, 15 * 60)
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(seconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NEXT, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(ctx: Context) {
        WorkManager.getInstance(ctx).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(NOW)
            cancelUniqueWork(NEXT)
        }
    }
}
