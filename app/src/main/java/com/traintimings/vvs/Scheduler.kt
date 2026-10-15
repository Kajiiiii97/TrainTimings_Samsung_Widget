package com.traintimings.vvs

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "vvs-periodic"
    private const val NOW = "vvs-now"
    private const val NEXT = "vvs-next"
    private const val URGENT = "vvs-urgent"

    /**
     * Every job must ask for network: Android (and Samsung's battery manager especially) can cut
     * background jobs without a network constraint off from the internet, which made fresh
     * refreshes fail with "Couldn't reach VVS".
     */
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Background refresh every 15 minutes (the shortest interval Android allows) to pick up delays. */
    fun ensurePeriodic(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Urgent refresh after a background download failed. Expedited jobs are let through more of
     * Android's background limits (Doze, app standby, battery saver) than normal ones.
     */
    fun refreshUrgent(ctx: Context) {
        val builder = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online)
        // Before Android 12 expedited work runs as a foreground service and needs a notification;
        // a normal job is fine there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(ctx).enqueueUniqueWork(URGENT, ExistingWorkPolicy.KEEP, builder.build())
    }

    /** Wake up just after the next train leaves so the widget rolls forward instead of showing a gone train. */
    fun scheduleNext(ctx: Context, nextDeparture: Instant?) {
        if (nextDeparture == null) return
        val seconds = (Duration.between(Instant.now(), nextDeparture).seconds + 20)
            .coerceIn(60, 15 * 60)
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(seconds, TimeUnit.SECONDS)
            .setConstraints(online)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NEXT, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(ctx: Context) {
        WorkManager.getInstance(ctx).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(NOW)
            cancelUniqueWork(NEXT)
            cancelUniqueWork(URGENT)
        }
    }
}
